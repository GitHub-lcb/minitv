package us.minitv.pcatalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.pcatalog.dto.PCatalogCandidate;
import us.minitv.pcatalog.dto.PCatalogCommentCount;
import us.minitv.pcatalog.dto.PCatalogEntryPick;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounts;
import us.minitv.pcatalog.dto.PCatalogFeatureStore;
import us.minitv.pcatalog.dto.PCatalogMaterialized;
import us.minitv.pcatalog.dto.PCatalogReactionTotal;
import us.minitv.pcatalog.dto.PCatalogViewerState;
import us.minitv.pcatalog.mapper.PCatalogSignalMapper;
import us.minitv.pcatalog.support.PCatalogFeedConfig;
import us.minitv.pcatalog.support.PCatalogOut;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * For-You feed orchestration - a 1:1 port of
 * {@code backend/app/modules/recommend/service.py}: recall, signed session
 * cursor, pagination and best-effort Redis caching.
 */
@Service
public class PCatalogFeedService {

    private static final String IMPRESSION_PREFIX = "feed:imp:";
    private static final String PLAN_PREFIX = "feed:plan:";

    private final PCatalogContentService contentService;
    private final PCatalogSignals signals;
    private final PCatalogRanking ranking;
    private final PCatalogSignalMapper signalMapper;
    private final PCatalogFeedCursor cursor;
    private final StringRedisTemplate redis;

    public PCatalogFeedService(PCatalogContentService contentService,
                               PCatalogSignals signals,
                               PCatalogRanking ranking,
                               PCatalogSignalMapper signalMapper,
                               PCatalogFeedCursor cursor,
                               StringRedisTemplate redis) {
        this.contentService = contentService;
        this.signals = signals;
        this.ranking = ranking;
        this.signalMapper = signalMapper;
        this.cursor = cursor;
        this.redis = redis;
    }

    /** GET /v1/feed - one page of the For-You feed. */
    public Map<String, Object> buildFeed(Long userId, String deviceId, String cursorRaw,
                                         Integer limit, String country) {
        int requested = limit == null ? PCatalogFeedConfig.DEFAULT_PAGE_SIZE : limit;
        int size = Math.max(1, Math.min(requested, PCatalogFeedConfig.MAX_PAGE_SIZE));

        PCatalogFeedCursor.Resolved resolved = cursor.decode(cursorRaw);

        String sessionId;
        String seed;
        List<Map<String, Object>> plan;
        int offset;
        if (resolved == null) {
            sessionId = randomHex(16);
            seed = randomHex(8);
            plan = buildPlan(userId, deviceId, country, seed, size);
            savePlan(sessionId, plan);
            offset = 0;
        } else {
            sessionId = resolved.sessionId();
            offset = resolved.offset();
            seed = resolved.seed();
            plan = loadPlan(sessionId);
            if (plan == null) {
                // Plan expired (or Redis was flushed): start a fresh session so the
                // client keeps scrolling instead of hitting a dead end.
                return buildFeed(userId, deviceId, null, size, country);
            }
        }

        PCatalogMaterialized materialized = materialize(plan, offset, size, userId);
        List<Long> shownDramaIds = new ArrayList<>();
        for (Map<String, Object> item : materialized.getItems()) {
            Object drama = item.get("drama");
            if (drama instanceof Map<?, ?> dramaMap && dramaMap.get("id") instanceof Number id) {
                shownDramaIds.add(id.longValue());
            }
        }
        recordImpressions(viewerKey(userId, deviceId), shownDramaIds);

        int nextOffset = offset + materialized.getConsumed();
        boolean hasMore = nextOffset < plan.size();
        return PCatalogOut.of(
                "session_id", sessionId,
                "page_size", size,
                "items", materialized.getItems(),
                "next_cursor", hasMore ? cursor.encode(sessionId, nextOffset, seed) : null,
                "has_more", hasMore,
                "preload", PCatalogOut.of("fetch_when_remaining", PCatalogFeedConfig.PRELOAD_REMAINING));
    }

    // ------------------------------------------------------------- plan build

    private List<Map<String, Object>> buildPlan(Long userId, String deviceId, String country,
                                                String seed, int limit) {
        LocalDateTime now = Times.now();
        PCatalogViewerState viewer = signals.viewerState(userId);

        List<DramaRow> dramas = signals.visibleDramas(country);
        List<Long> dramaIds = new ArrayList<>();
        for (DramaRow drama : dramas) {
            dramaIds.add(drama.getId());
        }
        if (dramaIds.isEmpty()) {
            return List.of();
        }

        PCatalogFeatureStore store = signals.buildStore(now, dramaIds, userId, viewer.isMember());
        store.setUnlockedEpisodes(viewer.getUnlocked());
        store.setProgress(viewer.getProgress());
        store.setImpressions(loadImpressions(viewerKey(userId, deviceId)));

        Map<Long, List<EpisodeRow>> episodesByDrama = signals.playableEpisodes(dramaIds);
        List<PCatalogCandidate> candidates = new ArrayList<>();
        for (DramaRow drama : dramas) {
            PCatalogEntryPick pick = signals.pickEntryEpisode(
                    drama,
                    episodesByDrama.getOrDefault(drama.getId(), List.of()),
                    viewer.isMember(),
                    viewer.getUnlocked(),
                    viewer.getProgress(),
                    store.getCompletedEpisodes());
            if (pick == null) {
                // Nothing watchable without paying -> not a feed slot.
                continue;
            }
            PCatalogCandidate candidate = new PCatalogCandidate();
            candidate.setDrama(drama);
            candidate.setEntryEpisode(pick.getEpisode());
            candidate.setResumeSeconds(pick.getResumeSeconds());
            candidate.setResumable(pick.isResumable());
            candidates.add(candidate);
        }

        List<PCatalogCandidate> ordered = ranking.rank(store, candidates, seed);
        assignSources(store, ordered);

        int poolSize = Math.max(PCatalogFeedConfig.RECALL_POOL_MULTIPLIER * limit,
                PCatalogFeedConfig.MIN_POOL_SIZE);
        List<Map<String, Object>> plan = new ArrayList<>();
        int take = Math.min(poolSize, ordered.size());
        for (int index = 0; index < take; index++) {
            PCatalogCandidate candidate = ordered.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("drama_id", candidate.getDrama().getId());
            entry.put("episode_id", candidate.getEntryEpisode().getId());
            entry.put("resume_seconds", candidate.getResumeSeconds());
            entry.put("source", candidate.getSource());
            entry.put("score", round4(candidate.getScore()));
            plan.add(entry);
        }
        return plan;
    }

    /** Label each slot with *why* it is here (pure telemetry, unlabelled stays explore). */
    private void assignSources(PCatalogFeatureStore store, List<PCatalogCandidate> ordered) {
        for (PCatalogCandidate candidate : ordered) {
            candidate.setSource("explore");
        }
        int resumed = 0;
        for (PCatalogCandidate candidate : ordered) {
            if (!candidate.isResumable()) {
                continue;
            }
            if (resumed >= PCatalogFeedConfig.RESUME_QUOTA) {
                break;
            }
            candidate.setSource("resume");
            resumed++;
        }

        int quota = PCatalogFeedConfig.SOURCE_QUOTA;
        label(top(ordered, candidate -> tagScore(store, candidate), quota), "affinity");
        label(top(ordered,
                candidate -> (double) store.getHot().getOrDefault(candidate.getDrama().getId(), 0L), quota),
                "trending");
        label(top(ordered,
                candidate -> candidate.getFeatures().getOrDefault("completion", 0.0), quota), "quality");
        label(top(ordered,
                candidate -> candidate.getFeatures().getOrDefault("freshness", 0.0), quota), "fresh");
    }

    private List<PCatalogCandidate> top(List<PCatalogCandidate> ordered,
                                        ToDoubleFunction<PCatalogCandidate> key, int quota) {
        List<PCatalogCandidate> pool = new ArrayList<>();
        for (PCatalogCandidate candidate : ordered) {
            if ("explore".equals(candidate.getSource())) {
                pool.add(candidate);
            }
        }
        pool.sort(Comparator.comparingDouble(key).reversed());
        return pool.subList(0, Math.min(quota, pool.size()));
    }

    private void label(List<PCatalogCandidate> candidates, String source) {
        for (PCatalogCandidate candidate : candidates) {
            if ("explore".equals(candidate.getSource())) {
                candidate.setSource(source);
            }
        }
    }

    private double tagScore(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        List<TagRow> tags = store.getTags().getOrDefault(candidate.getDrama().getId(), List.of());
        double best = 0.0;
        int seen = 0;
        for (TagRow tag : tags) {
            double score = store.getUserTagScores().getOrDefault(tag.getId(), 0.0);
            best = seen == 0 ? score : Math.max(best, score);
            seen++;
        }
        return seen == 0 ? 0.0 : best;
    }

    // ------------------------------------------------------------ materialise

    /** Turn plan entries into client payloads, re-checking they are still valid. */
    private PCatalogMaterialized materialize(List<Map<String, Object>> plan, int offset,
                                             int limit, Long userId) {
        PCatalogMaterialized materialized = new PCatalogMaterialized();
        if (offset >= plan.size()) {
            return materialized;
        }

        PCatalogViewerState viewer = signals.viewerState(userId);
        List<Map<String, Object>> entries = plan.subList(offset, plan.size());
        List<Long> dramaIds = new ArrayList<>();
        List<Long> episodeIds = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            dramaIds.add(asLong(entry.get("drama_id")));
            episodeIds.add(asLong(entry.get("episode_id")));
        }

        Map<Long, DramaRow> dramas = new LinkedHashMap<>();
        for (DramaRow drama : contentService.publishedDramasByIds(dramaIds)) {
            dramas.put(drama.getId(), drama);
        }
        Map<Long, EpisodeRow> episodes = new LinkedHashMap<>();
        for (EpisodeRow episode : contentService.playableEpisodesByIds(episodeIds)) {
            episodes.put(episode.getId(), episode);
        }
        Map<Long, List<TagRow>> tagsByDrama = contentService.tagsByDrama(dramas.keySet());
        Map<Long, PCatalogEpisodeCounts> counts = contentService.episodeCounts(dramas.keySet());
        Map<Long, Integer> commentCounts = commentCounts(episodes.keySet(), userId);
        Reactions reactions = reactionStats(dramas.keySet(), userId);

        int consumed = 0;
        for (Map<String, Object> entry : entries) {
            consumed++;
            DramaRow drama = dramas.get(asLong(entry.get("drama_id")));
            EpisodeRow episode = episodes.get(asLong(entry.get("episode_id")));
            if (drama == null || episode == null || !drama.getId().equals(episode.getDramaId())) {
                continue;
            }
            if (episode.getEpisodeNumber() > drama.getFreeEpisodeCount()
                    && !viewer.isMember() && !viewer.getUnlocked().contains(episode.getId())) {
                continue;
            }

            List<String> tagNames = new ArrayList<>();
            for (TagRow tag : tagsByDrama.getOrDefault(drama.getId(), List.of())) {
                tagNames.add(tag.getNameEn());
            }
            PCatalogEpisodeCounts dramaCounts = counts.get(drama.getId());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", "d" + drama.getId() + ":e" + episode.getId());
            item.put("drama", PCatalogOut.of(
                    "id", drama.getId(),
                    "title", drama.getTitle(),
                    "synopsis", drama.getSynopsis(),
                    "cover_url", drama.getCoverUrl(),
                    "tags", tagNames,
                    "episode_count", dramaCounts == null || dramaCounts.getTotal() == null
                            ? 0L : dramaCounts.getTotal(),
                    "published_episode_count", dramaCounts == null || dramaCounts.getPublished() == null
                            ? 0L : dramaCounts.getPublished(),
                    "free_episode_count", drama.getFreeEpisodeCount(),
                    "coin_price_per_episode", drama.getCoinPricePerEpisode(),
                    "play_count", drama.getPlayCount(),
                    "like_count", reactions.totals().getOrDefault(drama.getId(), 0L),
                    "liked", reactions.liked().contains(drama.getId())));
            item.put("episode", PCatalogOut.of(
                    "id", episode.getId(),
                    "episode_number", episode.getEpisodeNumber(),
                    "title", episode.getTitle(),
                    "duration_seconds", episode.getDurationSeconds(),
                    "free", episode.getEpisodeNumber() <= drama.getFreeEpisodeCount(),
                    "comment_count", commentCounts.getOrDefault(episode.getId(), 0)));
            item.put("resume_seconds", asInt(entry.get("resume_seconds")));
            item.put("entry_reason", entry.get("source"));
            item.put("score", entry.get("score"));
            materialized.getItems().add(item);

            if (materialized.getItems().size() >= limit) {
                break;
            }
        }
        materialized.setConsumed(consumed);
        return materialized;
    }

    private Map<Long, Integer> commentCounts(Collection<Long> episodeIds, Long userId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (episodeIds == null || episodeIds.isEmpty()) {
            return counts;
        }
        List<Long> ids = List.copyOf(episodeIds);
        for (PCatalogCommentCount row : signalMapper.approvedCommentCounts(ids)) {
            counts.put(row.getEpisodeId(), row.getTotal() == null ? 0 : row.getTotal().intValue());
        }
        if (userId != null) {
            // Own pending comments are visible to their author, so they count for
            // that viewer only; nobody else's feed number moves.
            for (PCatalogCommentCount row : signalMapper.pendingCommentCounts(ids, userId)) {
                int total = row.getTotal() == null ? 0 : row.getTotal().intValue();
                counts.merge(row.getEpisodeId(), total, Integer::sum);
            }
        }
        return counts;
    }

    private Reactions reactionStats(Collection<Long> dramaIds, Long userId) {
        if (dramaIds == null || dramaIds.isEmpty()) {
            return new Reactions(Map.of(), Set.of());
        }
        List<Long> ids = List.copyOf(dramaIds);
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (PCatalogReactionTotal row : signalMapper.likeTotals(ids)) {
            totals.put(row.getTargetId(), row.getTotal() == null ? 0L : row.getTotal());
        }
        Set<Long> liked = new LinkedHashSet<>();
        if (userId != null) {
            liked.addAll(signalMapper.likedDramaIds(userId, ids));
        }
        return new Reactions(totals, liked);
    }

    private record Reactions(Map<Long, Long> totals, Set<Long> liked) {
    }

    // -------------------------------------------------------------- redis bits

    /** Stable identity for fatigue tracking: the account, else the device. */
    static String viewerKey(Long userId, String deviceId) {
        if (userId != null) {
            return "u" + userId;
        }
        String raw = deviceId == null ? "anon" : deviceId.trim();
        if (raw.isEmpty()) {
            raw = "anon";
        }
        return "d" + raw;
    }

    private Map<Long, Integer> loadImpressions(String key) {
        Map<Long, Integer> impressions = new LinkedHashMap<>();
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(IMPRESSION_PREFIX + key);
            for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                String field = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                String unsigned = value.startsWith("-") ? value.substring(1) : value;
                if (!isDigits(field) || unsigned.isEmpty() || !isDigits(unsigned)) {
                    continue;
                }
                try {
                    impressions.put(Long.parseLong(field), Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    // Rows that do not round-trip are skipped, exactly like the Python filter.
                }
            }
        } catch (Exception ex) {
            // Redis down must not break the feed.
            return new LinkedHashMap<>();
        }
        return impressions;
    }

    private void recordImpressions(String key, List<Long> dramaIds) {
        if (dramaIds == null || dramaIds.isEmpty()) {
            return;
        }
        String redisKey = IMPRESSION_PREFIX + key;
        try {
            for (Long dramaId : dramaIds) {
                redis.opsForHash().increment(redisKey, String.valueOf(dramaId), 1L);
            }
            redis.expire(redisKey, Duration.ofSeconds(PCatalogFeedConfig.IMPRESSION_TTL_SECONDS));
        } catch (Exception ex) {
            // Cache only: a Redis failure must not fail the feed.
        }
    }

    private List<Map<String, Object>> loadPlan(String sessionId) {
        try {
            String raw = redis.opsForValue().get(PLAN_PREFIX + sessionId);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            return Json.mapper().readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception ex) {
            return null;
        }
    }

    private void savePlan(String sessionId, List<Map<String, Object>> plan) {
        try {
            redis.opsForValue().set(PLAN_PREFIX + sessionId, Json.write(plan),
                    Duration.ofSeconds(PCatalogFeedConfig.PLAN_TTL_SECONDS));
        } catch (Exception ex) {
            // Without the plan the next page just starts a fresh session.
        }
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /** Python round(value, 4): round-half-even on the exact binary value. */
    private static double round4(double value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
