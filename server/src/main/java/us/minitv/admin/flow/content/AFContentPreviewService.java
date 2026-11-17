package us.minitv.admin.flow.content;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import us.minitv.admin.flow.content.mapper.AFContentDramaMapper;
import us.minitv.admin.flow.content.mapper.AFContentEpisodeMapper;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.security.RedisStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /admin/v1/dramas/{drama_id}/preview}, ported from
 * {@code flow_router.preview} and the reachable part of
 * {@code playback/service.authorize}.
 *
 * <p>The admin preview authorises as an anonymous viewer
 * ({@code user_id=None}), so membership and coin unlocks are always false and
 * only the free-episode window can open playback. The fake-mode signing path
 * (local HMAC URLs) is implemented here; real mode delegates to the Bunny
 * gateway exactly like Python.</p>
 */
@Service
public class AFContentPreviewService {

    private static final int TOKEN_TTL_SECONDS = 300;
    private static final int[] RENDITION_LADDER = {240, 360, 480, 720, 1080, 1440, 2160};

    private final AFContentDramaMapper dramas;
    private final AFContentEpisodeMapper episodes;
    private final AFContentBunny bunny;
    private final RedisStore redis;
    private final Environment environment;

    public AFContentPreviewService(AFContentDramaMapper dramas,
                                   AFContentEpisodeMapper episodes,
                                   AFContentBunny bunny,
                                   RedisStore redis,
                                   Environment environment) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.bunny = bunny;
        this.redis = redis;
        this.environment = environment;
    }

    /** {@code preview}: the admin player bootstrap payload. */
    public Map<String, Object> preview(long dramaId, Long episodeId) {
        DramaRow drama = dramas.findById(dramaId);
        if (drama == null) {
            throw new ApiException(404, "drama not found");
        }
        EpisodeRow episode;
        if (episodeId != null && episodeId != 0) {
            episode = episodes.findById(episodeId);
        } else {
            episode = episodes.findFirstByDramaOrdered(dramaId);
        }
        List<EpisodeRow> rows = episodes.listByDramaOrdered(dramaId);
        Map<String, Object> playback = null;
        if (episode != null && "published".equals(drama.getStatus())
                && "published".equals(episode.getStatus())) {
            playback = authorize(drama, episode);
        }
        Map<String, Object> current = null;
        if (episode != null) {
            current = new LinkedHashMap<>();
            current.put("id", episode.getId());
            current.put("number", episode.getEpisodeNumber());
            current.put("title", episode.getTitle());
            current.put("subtitle_url", episode.getSubtitleUrl());
        }
        List<Map<String, Object>> listed = new ArrayList<>(rows.size());
        for (EpisodeRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("number", row.getEpisodeNumber());
            item.put("status", row.getStatus());
            listed.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preview_kind", "player");
        result.put("drama_id", drama.getId());
        result.put("title", drama.getTitle());
        result.put("current_episode", current);
        result.put("episodes", listed);
        result.put("playback", playback);
        return result;
    }

    /**
     * {@code authorize} for an anonymous admin-preview viewer: published rows
     * only, free window (or nothing) decides access, then signed playback URLs.
     */
    Map<String, Object> authorize(DramaRow drama, EpisodeRow episode) {
        if (episode.getDramaId() == null || drama.getId() == null
                || !episode.getDramaId().equals(drama.getId())
                || !"published".equals(drama.getStatus())
                || !"published".equals(episode.getStatus())) {
            Map<String, Object> denied = new LinkedHashMap<>();
            denied.put("allowed", false);
            denied.put("reason", "not_available");
            return denied;
        }
        // The admin preview passes no country, so the region gate never fires.
        int freeCount = drama.getFreeEpisodeCount() == null ? 0 : drama.getFreeEpisodeCount();
        int number = episode.getEpisodeNumber() == null ? 0 : episode.getEpisodeNumber();
        boolean free = number <= freeCount;
        boolean member = false;
        boolean unlocked = false;
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
                ? String.valueOf(episode.getId())
                : episode.getBunnyVideoId();
        Map<String, Object> signed;
        if (bunny.live()) {
            signed = bunny.signStreamPlayback(videoId, TOKEN_TTL_SECONDS);
        } else {
            signed = signPath("/videos/" + videoId + "/", videoSecret(), TOKEN_TTL_SECONDS,
                    generation(episode.getId()));
        }
        List<String> renditions = renditionsFor(episode.getWidth(), episode.getHeight(), false);
        Object subtitle = null;
        String subtitleUrl = episode.getSubtitleUrl() == null ? "" : episode.getSubtitleUrl();
        if (!subtitleUrl.isEmpty()) {
            if (bunny.live()) {
                subtitle = bunny.signImageUrl(subtitleUrl, TOKEN_TTL_SECONDS);
            } else {
                subtitle = signPath("/images/subs/" + episode.getId() + "/", imageSecret(), TOKEN_TTL_SECONDS, 0);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", true);
        result.put("expires_at", signed.get("expires_at"));
        result.put("url", signed.get("url"));
        result.put("renditions", renditions);
        result.put("subtitle", subtitle);
        result.put("episode_id", episode.getId());
        result.put("episode_number", episode.getEpisodeNumber());
        result.put("title", episode.getTitle());
        result.put("duration_seconds", episode.getDurationSeconds());
        return result;
    }

    /** {@code playback.service.sign_path}: local HMAC directory token. */
    Map<String, Object> signPath(String path, byte[] secret, int ttl, long gen) {
        long expires = Instant.now().getEpochSecond() + ttl;
        String message = path + "|" + expires + "|" + gen;
        String token = hmacHex(secret, message.getBytes(StandardCharsets.UTF_8));
        String url = cdnBase() + path + "?token=" + token + "&expires=" + expires + "&gen=" + gen;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("path", path);
        signed.put("token", token);
        signed.put("expires_at", expires);
        signed.put("url", url);
        signed.put("gen", gen);
        return signed;
    }

    /** {@code playback.service.renditions_for}: best-effort rendition hints. */
    List<String> renditionsFor(Integer width, Integer height, boolean member) {
        int shortSide = 0;
        for (Integer value : new Integer[]{width, height}) {
            int candidate = value == null ? 0 : value;
            if (candidate != 0 && (shortSide == 0 || candidate < shortSide)) {
                shortSide = candidate;
            }
        }
        List<Integer> ladder = new ArrayList<>();
        if (shortSide != 0) {
            for (int step : RENDITION_LADDER) {
                if (step <= shortSide) {
                    ladder.add(step);
                }
            }
        } else {
            ladder.add(360);
            ladder.add(720);
            ladder.add(1080);
        }
        String memberOnly = environment.getProperty("MEMBER_ONLY_1080", "false");
        if (memberOnly != null && "true".equalsIgnoreCase(memberOnly.trim()) && !member) {
            ladder.removeIf(step -> step >= 1080);
        }
        List<String> names = new ArrayList<>(ladder.size());
        for (int step : ladder) {
            names.add(step + "P");
        }
        return names;
    }

    private long generation(long episodeId) {
        String raw = redis.get("playback_gen:" + episodeId);
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exc) {
            return 0L;
        }
    }

    private byte[] videoSecret() {
        return secret("PLAYBACK_TOKEN_KEY", "local-playback-token");
    }

    private byte[] imageSecret() {
        return secret("IMAGE_TOKEN_KEY", "local-image-token");
    }

    private byte[] secret(String name, String fallback) {
        String value = environment.getProperty(name, fallback);
        return (value == null ? fallback : value).getBytes(StandardCharsets.UTF_8);
    }

    private String cdnBase() {
        String value = environment.getProperty("PLAYBACK_CDN_BASE", "https://cdn.local");
        String base = value == null ? "https://cdn.local" : value.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String hmacHex(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(message);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException exc) {
            throw new IllegalStateException(exc);
        }
    }
}
