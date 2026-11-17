package us.minitv.pcatalog.service;

import org.springframework.stereotype.Service;
import us.minitv.billing.row.SubscriptionRow;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.pcatalog.dto.PCatalogAffinityEvent;
import us.minitv.pcatalog.dto.PCatalogAffinityReaction;
import us.minitv.pcatalog.dto.PCatalogCandidate;
import us.minitv.pcatalog.dto.PCatalogDramaAggregate;
import us.minitv.pcatalog.dto.PCatalogEntryPick;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounts;
import us.minitv.pcatalog.dto.PCatalogFeatureStore;
import us.minitv.pcatalog.dto.PCatalogViewerState;
import us.minitv.pcatalog.mapper.PCatalogSignalMapper;
import us.minitv.pcatalog.support.PCatalogFeedConfig;
import us.minitv.playback.row.PlaybackProgressRow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Feature store, viewer state and scoring signals for the For-You feed - a 1:1
 * port of {@code backend/app/modules/recommend/signals.py}.
 *
 * <p>Every statistic is fetched with one grouped query per source table, and
 * every signal is a pure function returning a value in [0, 1] (or
 * {@link PCatalogFeedConfig#NEUTRAL} without evidence).
 */
@Service
public class PCatalogSignals {

    private final PCatalogSignalMapper signalMapper;
    private final PCatalogContentService contentService;
    private final Map<String, BiFunction<PCatalogFeatureStore, PCatalogCandidate, Double>> signals =
            new LinkedHashMap<>();

    public PCatalogSignals(PCatalogSignalMapper signalMapper, PCatalogContentService contentService) {
        this.signalMapper = signalMapper;
        this.contentService = contentService;
        signals.put("completion", this::completionSignal);
        signals.put("affinity", this::affinitySignal);
        signals.put("hotness", this::hotnessSignal);
        signals.put("freshness", this::freshnessSignal);
        signals.put("depth", this::depthSignal);
    }

    /** Registry in Python dict order: completion, affinity, hotness, freshness, depth. */
    public Map<String, BiFunction<PCatalogFeatureStore, PCatalogCandidate, Double>> registry() {
        return signals;
    }

    // ------------------------------------------------------------------ store

    public PCatalogFeatureStore buildStore(LocalDateTime now, List<Long> dramaIds,
                                           Long userId, boolean member) {
        PCatalogFeatureStore store = new PCatalogFeatureStore();
        store.setNow(now);
        store.setMember(member);
        if (dramaIds == null || dramaIds.isEmpty()) {
            return store;
        }
        loadDramaAggregates(store, dramaIds);
        store.setTags(contentService.tagsByDrama(dramaIds));
        if (userId != null) {
            loadViewerState(store, userId);
            store.setUserTagScores(viewerTagAffinity(now, userId));
            double maxTagScore = 1.0;
            if (!store.getUserTagScores().isEmpty()) {
                double max = Collections.max(store.getUserTagScores().values());
                maxTagScore = max == 0.0 ? 1.0 : max;
            }
            store.setMaxTagScore(maxTagScore);
        }
        return store;
    }

    private void loadDramaAggregates(PCatalogFeatureStore store, List<Long> dramaIds) {
        LocalDateTime longWindow = store.getNow().minusDays(PCatalogFeedConfig.COMPLETION_WINDOW_DAYS);
        LocalDateTime shortWindow = store.getNow().minusDays(PCatalogFeedConfig.HOTNESS_WINDOW_DAYS);
        for (PCatalogDramaAggregate row : signalMapper.dramaAggregates(dramaIds, longWindow, shortWindow)) {
            long plays = row.getPlays() == null ? 0L : row.getPlays();
            long completes = row.getCompletes() == null ? 0L : row.getCompletes();
            long hot = row.getHot() == null ? 0L : row.getHot();
            store.getPlays().put(row.getDramaId(), plays);
            store.getCompletes().put(row.getDramaId(), completes);
            store.getHot().put(row.getDramaId(), hot);
            store.setMaxHot(Math.max(store.getMaxHot(), hot));
        }

        for (PCatalogEpisodeCounts row : contentService.episodeCounts(dramaIds).values()) {
            store.getTotal().put(row.getDramaId(), row.getTotal() == null ? 0L : row.getTotal());
            store.getPublished().put(row.getDramaId(), row.getPublished() == null ? 0L : row.getPublished());
        }

        long totalPlays = nullSafe(signalMapper.countEvents("start", longWindow));
        long totalCompletes = nullSafe(signalMapper.countEvents("complete", longWindow));
        store.setGlobalCompletion(totalPlays == 0L
                ? PCatalogFeedConfig.DEFAULT_GLOBAL_COMPLETION
                : (double) totalCompletes / (double) totalPlays);
    }

    private void loadViewerState(PCatalogFeatureStore store, long userId) {
        Map<Long, Integer> progress = new HashMap<>();
        for (PlaybackProgressRow row : signalMapper.progressForUser(userId)) {
            progress.put(row.getEpisodeId(),
                    row.getPositionSeconds() == null ? 0 : row.getPositionSeconds());
        }
        store.setProgress(progress);
        store.setCompletedEpisodes(new HashSet<>(signalMapper.completedEpisodeIds(userId)));
        store.setUnlockedEpisodes(new HashSet<>(signalMapper.unlockedEpisodeIds(userId)));
    }

    private Map<Long, Double> viewerTagAffinity(LocalDateTime now, long userId) {
        LocalDateTime cutoff = now.minusDays(PCatalogFeedConfig.AFFINITY_WINDOW_DAYS);
        double decayTau = PCatalogFeedConfig.param("AFFINITY_DECAY_DAYS", PCatalogFeedConfig.AFFINITY_DECAY_DAYS);
        Map<Long, Double> scores = new LinkedHashMap<>();

        for (PCatalogAffinityEvent event : signalMapper.affinityEvents(userId, cutoff)) {
            double weight = affinityWeight(event.getKind());
            scores.merge(event.getTagId(), weight * decay(now, event.getCreatedAt(), decayTau), Double::sum);
        }
        for (PCatalogAffinityReaction reaction : signalMapper.affinityReactions(userId, cutoff)) {
            scores.merge(reaction.getTagId(), 2.0 * decay(now, reaction.getCreatedAt(), decayTau), Double::sum);
        }
        return scores;
    }

    /** config.AFFINITY_WEIGHTS, with "progress" as the fallback for unknown kinds. */
    private static double affinityWeight(String kind) {
        if ("complete".equals(kind)) {
            return 3.0;
        }
        if ("like".equals(kind)) {
            return 2.0;
        }
        if ("start".equals(kind)) {
            return 1.0;
        }
        return 0.5;
    }

    private static double decay(LocalDateTime now, LocalDateTime moment, double tau) {
        if (moment == null) {
            return 1.0;
        }
        Duration elapsed = Duration.between(moment, now);
        double seconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
        double ageDays = Math.max(0.0, seconds / 86400.0);
        return Math.exp(-ageDays / tau);
    }

    // ---------------------------------------------------------------- signals

    /** Smoothed completion rate pulled towards the platform mean. */
    public double completionSignal(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        Long dramaId = candidate.getDrama().getId();
        long starts = store.getPlays().getOrDefault(dramaId, 0L);
        long completes = store.getCompletes().getOrDefault(dramaId, 0L);
        double alpha = PCatalogFeedConfig.param("COMPLETION_PRIOR_STRENGTH",
                PCatalogFeedConfig.COMPLETION_PRIOR_STRENGTH);
        return (completes + alpha * store.getGlobalCompletion()) / (starts + alpha);
    }

    /** How well the drama's tags match this viewer's history (0.5..1.0). */
    public double affinitySignal(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        if (store.getUserTagScores().isEmpty()) {
            return PCatalogFeedConfig.NEUTRAL;
        }
        List<TagRow> tags = store.getTags().getOrDefault(candidate.getDrama().getId(), List.of());
        if (tags.isEmpty()) {
            return PCatalogFeedConfig.NEUTRAL;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (TagRow tag : tags) {
            best = Math.max(best, store.getUserTagScores().getOrDefault(tag.getId(), 0.0));
        }
        return PCatalogFeedConfig.NEUTRAL
                + PCatalogFeedConfig.NEUTRAL * Math.min(1.0, best / store.getMaxTagScore());
    }

    /** Recent watch volume, log-scaled so one hit show cannot dominate. */
    public double hotnessSignal(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        if (store.getMaxHot() <= 0) {
            return PCatalogFeedConfig.NEUTRAL;
        }
        long hot = store.getHot().getOrDefault(candidate.getDrama().getId(), 0L);
        return Math.log1p(hot) / Math.log1p(store.getMaxHot());
    }

    /** Exponential decay from the publish date. */
    public double freshnessSignal(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        DramaRow drama = candidate.getDrama();
        LocalDateTime moment = drama.getPublishedAt() != null ? drama.getPublishedAt()
                : drama.getUpdatedAt() != null ? drama.getUpdatedAt() : drama.getCreatedAt();
        if (moment == null) {
            return PCatalogFeedConfig.NEUTRAL;
        }
        Duration elapsed = Duration.between(moment, store.getNow());
        double seconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
        double ageDays = Math.max(0.0, seconds / 86400.0);
        double tau = PCatalogFeedConfig.param("FRESHNESS_TAU_DAYS", PCatalogFeedConfig.FRESHNESS_TAU_DAYS);
        return Math.exp(-ageDays / tau);
    }

    /** Is there enough published to be worth getting into? */
    public double depthSignal(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        long published = store.getPublished().getOrDefault(candidate.getDrama().getId(), 0L);
        double full = PCatalogFeedConfig.param("DEPTH_FULL_EPISODES", 10.0);
        return full > 0 ? Math.min(1.0, published / full) : PCatalogFeedConfig.NEUTRAL;
    }

    /** The tag this drama is counted under for diversity: main-category tags win. */
    public TagRow mainTag(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        List<TagRow> tags = store.getTags().getOrDefault(candidate.getDrama().getId(), List.of());
        if (tags.isEmpty()) {
            return null;
        }
        for (TagRow tag : tags) {
            if ("main".equals(tag.getCategory())) {
                return tag;
            }
        }
        return tags.get(0);
    }

    // -------------------------------------------------------------- viewers

    /** (member, unlocked episode ids, resume seconds) for one viewer. */
    public PCatalogViewerState viewerState(Long userId) {
        PCatalogViewerState state = new PCatalogViewerState();
        if (userId == null) {
            return state;
        }
        state.setMember(subscriptionActive(userId));
        Map<Long, Integer> progress = new HashMap<>();
        for (PlaybackProgressRow row : signalMapper.progressForUser(userId)) {
            progress.put(row.getEpisodeId(),
                    row.getPositionSeconds() == null ? 0 : row.getPositionSeconds());
        }
        state.setProgress(progress);
        state.setUnlocked(new HashSet<>(signalMapper.unlockedEpisodeIds(userId)));
        return state;
    }

    /** playback.subscription_active: an active subscription row whose access window ends in the future. */
    public boolean subscriptionActive(long userId) {
        List<SubscriptionRow> rows = signalMapper.activeSubscriptions(userId);
        if (rows.size() > 1) {
            // Python's scalar_one_or_none() raised on this broken invariant.
            throw new IllegalStateException("multiple active subscriptions for user " + userId);
        }
        if (rows.isEmpty()) {
            return false;
        }
        SubscriptionRow row = rows.get(0);
        LocalDateTime now = Times.now();
        return (row.getCurrentPeriodEnd() != null && row.getCurrentPeriodEnd().isAfter(now))
                || (row.getPassEnd() != null && row.getPassEnd().isAfter(now));
    }

    /** Published episodes that actually have a video, grouped by drama, in order. */
    public Map<Long, List<EpisodeRow>> playableEpisodes(List<Long> dramaIds) {
        Map<Long, List<EpisodeRow>> grouped = new LinkedHashMap<>();
        for (EpisodeRow episode : contentService.playableEpisodesByDramaIds(dramaIds)) {
            grouped.computeIfAbsent(episode.getDramaId(), key -> new ArrayList<>()).add(episode);
        }
        return grouped;
    }

    /**
     * Choose the episode a feed slot opens on. Locked episodes never become the
     * entry point: a paywalled drama drops out instead of teasing.
     */
    public PCatalogEntryPick pickEntryEpisode(DramaRow drama, List<EpisodeRow> episodes,
                                              boolean member, Set<Long> unlocked,
                                              Map<Long, Integer> progress, Set<Long> completed) {
        List<EpisodeRow> watchable = new ArrayList<>();
        for (EpisodeRow episode : episodes) {
            boolean free = episode.getEpisodeNumber() <= drama.getFreeEpisodeCount();
            if (free || member || unlocked.contains(episode.getId())) {
                watchable.add(episode);
            }
        }
        if (watchable.isEmpty()) {
            return null;
        }

        double maxRatio = PCatalogFeedConfig.param("RESUME_MAX_RATIO", PCatalogFeedConfig.RESUME_MAX_RATIO);
        for (EpisodeRow episode : watchable) {
            int position = progress.getOrDefault(episode.getId(), 0);
            if (position > 0 && !completed.contains(episode.getId())) {
                Integer duration = episode.getDurationSeconds();
                double ratio = duration != null && duration != 0
                        ? position / (double) duration : 0.0;
                if (ratio < maxRatio) {
                    return pick(episode, position, true);
                }
            }
        }
        for (EpisodeRow episode : watchable) {
            if (!completed.contains(episode.getId())) {
                return pick(episode, 0, false);
            }
        }
        return pick(watchable.get(0), 0, false);
    }

    private static PCatalogEntryPick pick(EpisodeRow episode, int resumeSeconds, boolean resumable) {
        PCatalogEntryPick pick = new PCatalogEntryPick();
        pick.setEpisode(episode);
        pick.setResumeSeconds(resumeSeconds);
        pick.setResumable(resumable);
        return pick;
    }

    /** Published dramas the caller's region is allowed to see. */
    public List<DramaRow> visibleDramas(String country) {
        List<DramaRow> dramas = contentService.visiblePublishedDramas();
        if (country == null) {
            return dramas;
        }
        String upper = country.trim().toUpperCase(Locale.ROOT);
        List<DramaRow> visible = new ArrayList<>();
        for (DramaRow drama : dramas) {
            String allowlist = drama.getCountryAllowlist();
            if (allowlist == null || allowlist.isEmpty()) {
                visible.add(drama);
                continue;
            }
            Set<String> codes = new HashSet<>();
            for (String code : allowlist.split(",")) {
                if (!code.trim().isEmpty()) {
                    codes.add(code.trim().toUpperCase(Locale.ROOT));
                }
            }
            if (codes.contains(upper)) {
                visible.add(drama);
            }
        }
        return visible;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
