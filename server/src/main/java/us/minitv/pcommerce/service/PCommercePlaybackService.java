package us.minitv.pcommerce.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.pcommerce.dto.PCommerceEpisodeCount;
import us.minitv.pcommerce.dto.PCommerceEpisodePosition;
import us.minitv.pcommerce.mapper.PCommercePlaybackMapper;
import us.minitv.pcommerce.mapper.PCommerceSocialMapper;
import us.minitv.pcommerce.mapper.PCommerceWalletMapper;
import us.minitv.pcommerce.support.PCommerceBunny;
import us.minitv.pcommerce.support.PCommerceCrypto;
import us.minitv.pcommerce.support.PCommerceEnv;
import us.minitv.playback.row.PlaybackEventRow;
import us.minitv.security.RedisStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Playback authorization and the drama play page, ported from
 * {@code app/modules/playback/service.py}.
 *
 * <p>Tokens are directory-scoped HMAC, default 300 seconds; image and video
 * secrets differ so a cover token cannot be replayed as a video URL.</p>
 */
@Service
public class PCommercePlaybackService {

    public static final int TOKEN_TTL_SECONDS = 300;

    private static final String VIDEO_PREFIX = "/videos/";
    private static final String IMAGE_PREFIX = "/images/";
    private static final String ENTITLEMENT_TYPE_COIN = "coin";

    /** Bunny's transcode ladder, in short-side pixels. */
    private static final List<Integer> RENDITION_LADDER =
            List.of(240, 360, 480, 720, 1080, 1440, 2160);

    private final PCommercePlaybackMapper playback;
    private final PCommerceWalletMapper wallet;
    private final PCommerceSocialMapper social;
    private final PCommerceBillingService billing;
    private final PCommerceContentService content;
    private final PCommerceBunny bunny;
    private final PCommerceEnv env;
    private final RedisStore redis;

    public PCommercePlaybackService(PCommercePlaybackMapper playback,
                                    PCommerceWalletMapper wallet,
                                    PCommerceSocialMapper social,
                                    PCommerceBillingService billing,
                                    PCommerceContentService content,
                                    PCommerceBunny bunny,
                                    PCommerceEnv env,
                                    RedisStore redis) {
        this.playback = playback;
        this.wallet = wallet;
        this.social = social;
        this.billing = billing;
        this.content = content;
        this.bunny = bunny;
        this.env = env;
        this.redis = redis;
    }

    // ------------------------------------------------------------- authorization

    /** playback.authorize: mint a playable URL or explain why it is locked. */
    public Map<String, Object> authorize(long dramaId, long episodeId, Long userId, String country) {
        DramaRow drama = content.findDrama(dramaId);
        EpisodeRow episode = content.findEpisode(episodeId);
        if (drama == null || episode == null || episode.getDramaId() != dramaId
                || !"published".equals(drama.getStatus())
                || !"published".equals(episode.getStatus())) {
            throw new ApiException(404, "not available");
        }
        String allowlist = drama.getCountryAllowlist();
        if (allowlist != null && !allowlist.isEmpty() && country != null && !country.isEmpty()) {
            Set<String> allowedCountries = new HashSet<>();
            for (String item : allowlist.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    allowedCountries.add(trimmed.toUpperCase(Locale.ROOT));
                }
            }
            if (!allowedCountries.contains(country.toUpperCase(Locale.ROOT))) {
                Map<String, Object> blocked = new LinkedHashMap<>();
                blocked.put("allowed", false);
                blocked.put("reason", "region_blocked");
                return blocked;
            }
        }

        boolean free = episode.getEpisodeNumber() <= drama.getFreeEpisodeCount();
        boolean member = subscriptionActive(userId);
        boolean unlocked = userId != null
                && wallet.findEntitlement(userId, episode.getId(), ENTITLEMENT_TYPE_COIN) != null;
        if (!(free || member || unlocked)) {
            Map<String, Object> unlock = new LinkedHashMap<>();
            unlock.put("coin_price", drama.getCoinPricePerEpisode());
            unlock.put("membership_available", true);
            Map<String, Object> locked = new LinkedHashMap<>();
            locked.put("allowed", false);
            locked.put("reason", "locked");
            locked.put("unlock", unlock);
            return locked;
        }

        String videoId = episode.getBunnyVideoId() == null || episode.getBunnyVideoId().isEmpty()
                ? String.valueOf(episode.getId()) : episode.getBunnyVideoId();
        Map<String, Object> signed;
        if (bunny.live()) {
            signed = bunny.signStreamPlayback(videoId, TOKEN_TTL_SECONDS);
        } else {
            signed = signPath(VIDEO_PREFIX + videoId + "/", env.videoSecret(),
                    TOKEN_TTL_SECONDS, generation(episode.getId()));
        }
        List<String> renditions = renditionsFor(episode.getWidth(), episode.getHeight(), member);
        Object subtitle = null;
        if (episode.getSubtitleUrl() != null && !episode.getSubtitleUrl().isEmpty()) {
            if (bunny.live()) {
                subtitle = bunny.signImageUrl(episode.getSubtitleUrl(), TOKEN_TTL_SECONDS);
            } else {
                subtitle = signPath(IMAGE_PREFIX + "subs/" + episode.getId() + "/",
                        env.imageSecret(), TOKEN_TTL_SECONDS, 0);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", true);
        out.put("expires_at", signed.get("expires_at"));
        out.put("url", signed.get("url"));
        out.put("renditions", renditions);
        out.put("subtitle", subtitle);
        out.put("episode_id", episode.getId());
        out.put("episode_number", episode.getEpisodeNumber());
        out.put("title", episode.getTitle());
        out.put("duration_seconds", episode.getDurationSeconds());
        return out;
    }

    /** playback.subscription_active */
    public boolean subscriptionActive(Long userId) {
        return userId != null && billing.accessUntil(userId) != null;
    }

    // ------------------------------------------------------------- events

    /** playback.record_event: store the event, bump play_count on "start". */
    @Transactional
    public void recordEvent(long episodeId, String kind, int positionSeconds, Long userId) {
        if (!"start".equals(kind) && !"progress".equals(kind) && !"complete".equals(kind)) {
            throw new IllegalArgumentException("unknown playback event kind: " + kind);
        }
        PlaybackEventRow event = new PlaybackEventRow();
        event.setUserId(userId);
        event.setEpisodeId(episodeId);
        event.setKind(kind);
        event.setPositionSeconds(positionSeconds);
        playback.insertEvent(event);
        if ("start".equals(kind)) {
            Long dramaId = playback.selectEpisodeDramaId(episodeId);
            if (dramaId != null) {
                playback.incrementPlayCount(dramaId);
            }
        }
        if (userId != null) {
            playback.upsertProgress(userId, episodeId, positionSeconds);
        }
    }

    // ------------------------------------------------------------- drama play page

    /** GET /v1/dramas/{drama_id}/play. */
    public Map<String, Object> dramaPlay(long dramaId, Long userId) {
        DramaRow drama = content.getPublishedDrama(dramaId);
        if (drama == null) {
            throw new ApiException(404, "drama not found");
        }
        boolean member = subscriptionActive(userId);

        Map<Long, Integer> progressRows = new HashMap<>();
        Set<Long> completed = new HashSet<>();
        Set<Long> unlocked = new LinkedHashSet<>();
        if (userId != null) {
            for (PCommerceEpisodePosition row : playback.progressByUser(userId)) {
                progressRows.put(row.getEpisodeId(),
                        row.getPositionSeconds() == null ? 0 : row.getPositionSeconds());
            }
            completed.addAll(playback.completedEpisodeIds(userId));
            for (Long episodeId : wallet.entitlementEpisodeIds(userId, ENTITLEMENT_TYPE_COIN)) {
                unlocked.add(episodeId);
            }
        }

        List<EpisodeRow> episodes = content.listPublishedEpisodes(dramaId);
        Map<Long, Integer> commentCounts = new HashMap<>();
        if (!episodes.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (EpisodeRow episode : episodes) {
                ids.add(episode.getId());
            }
            for (PCommerceEpisodeCount row : social.approvedCommentCounts(ids)) {
                commentCounts.put(row.getEpisodeId(), row.getTotal().intValue());
            }
            if (userId != null) {
                for (PCommerceEpisodeCount row : social.pendingCommentCounts(ids, userId)) {
                    commentCounts.merge(row.getEpisodeId(), row.getTotal().intValue(), Integer::sum);
                }
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> resume = null;
        for (EpisodeRow episode : episodes) {
            boolean free = episode.getEpisodeNumber() <= drama.getFreeEpisodeCount();
            boolean locked = !(free || member || unlocked.contains(episode.getId()));
            int position = progressRows.getOrDefault(episode.getId(), 0);
            Map<String, Object> unlock = null;
            if (locked) {
                unlock = new LinkedHashMap<>();
                unlock.put("coin_price", drama.getCoinPricePerEpisode());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", episode.getId());
            item.put("episode_number", episode.getEpisodeNumber());
            item.put("title", episode.getTitle());
            item.put("duration_seconds", episode.getDurationSeconds());
            item.put("free", free);
            item.put("locked", locked);
            item.put("progress_seconds", position);
            item.put("completed", completed.contains(episode.getId()));
            item.put("comment_count", commentCounts.getOrDefault(episode.getId(), 0));
            item.put("unlock", unlock);
            items.add(item);
            // Resume = the first watchable episode started but not finished;
            // otherwise the first watchable episode at all.
            if (resume == null && !locked && position > 0 && !completed.contains(episode.getId())) {
                resume = new LinkedHashMap<>();
                resume.put("episode_id", episode.getId());
                resume.put("position_seconds", position);
            }
        }
        if (resume == null) {
            for (Map<String, Object> item : items) {
                if (!Boolean.TRUE.equals(item.get("locked"))) {
                    resume = new LinkedHashMap<>();
                    resume.put("episode_id", item.get("id"));
                    resume.put("position_seconds", 0);
                    break;
                }
            }
        }

        List<String> tags = new ArrayList<>();
        for (TagRow tag : content.tagsOf(drama.getId())) {
            tags.add(tag.getNameEn());
        }
        long likeCount = social.countReactions("drama", drama.getId(), "like");
        long commentCount = social.countDramaComments(drama.getId());
        Set<String> mine = new HashSet<>();
        if (userId != null) {
            mine.addAll(social.userReactionKinds(userId, "drama", drama.getId()));
        }

        Map<String, Object> dramaPayload = new LinkedHashMap<>();
        dramaPayload.put("id", drama.getId());
        dramaPayload.put("title", drama.getTitle());
        dramaPayload.put("synopsis", drama.getSynopsis());
        dramaPayload.put("cover_url", drama.getCoverUrl());
        dramaPayload.put("tags", tags);
        dramaPayload.put("free_episode_count", drama.getFreeEpisodeCount());
        dramaPayload.put("coin_price_per_episode", drama.getCoinPricePerEpisode());
        dramaPayload.put("play_count", drama.getPlayCount());
        dramaPayload.put("like_count", likeCount);
        dramaPayload.put("comment_count", commentCount);
        dramaPayload.put("liked", mine.contains("like"));
        dramaPayload.put("favorited", mine.contains("favorite"));

        List<Long> unlockedIds = new ArrayList<>(unlocked);
        java.util.Collections.sort(unlockedIds);
        Map<String, Object> entitlement = new LinkedHashMap<>();
        entitlement.put("member", member);
        entitlement.put("unlocked_episode_ids", unlockedIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drama", dramaPayload);
        out.put("items", items);
        out.put("resume", resume);
        out.put("entitlement", entitlement);
        return out;
    }

    // ------------------------------------------------------------- token helpers

    /** playback.sign_path: valueless HMAC over "{path}|{expires}|{gen}". */
    public Map<String, Object> signPath(String path, byte[] secret, int ttl, long gen) {
        if (ttl > TOKEN_TTL_SECONDS) {
            throw new IllegalArgumentException("playback token ttl cannot exceed 300 seconds");
        }
        long expires = PCommerceCrypto.epochSeconds() + ttl;
        String message = path + "|" + expires + "|" + gen;
        String token = PCommerceCrypto.hmacSha256Hex(secret, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String url = env.cdnBase() + path + "?token=" + token + "&expires=" + expires + "&gen=" + gen;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("token", token);
        out.put("expires_at", expires);
        out.put("url", url);
        out.put("gen", gen);
        return out;
    }

    /** playback.renditions_for: best-effort rendition names for the source. */
    public List<String> renditionsFor(Integer width, Integer height, boolean member) {
        int shortSide = 0;
        for (Integer value : new Integer[]{width, height}) {
            if (value != null && value != 0 && (shortSide == 0 || value < shortSide)) {
                shortSide = value;
            }
        }
        List<Integer> ladder = new ArrayList<>();
        if (shortSide > 0) {
            for (Integer step : RENDITION_LADDER) {
                if (step <= shortSide) {
                    ladder.add(step);
                }
            }
        } else {
            ladder = new ArrayList<>(List.of(360, 720, 1080));
        }
        if (env.memberOnly1080() && !member) {
            ladder.removeIf(step -> step >= 1080);
        }
        List<String> out = new ArrayList<>();
        for (Integer step : ladder) {
            out.add(step + "P");
        }
        return out;
    }

    /** playback.generation: Redis counter bumped when an episode video is replaced. */
    private long generation(long episodeId) {
        String raw = redis.get("playback_gen:" + episodeId);
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("bad playback generation for episode " + episodeId, ex);
        }
    }
}
