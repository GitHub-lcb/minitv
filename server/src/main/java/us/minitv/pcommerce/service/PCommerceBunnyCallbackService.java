package us.minitv.pcommerce.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.MediaUploadRow;
import us.minitv.pcommerce.mapper.PCommerceAuditMapper;
import us.minitv.pcommerce.mapper.PCommerceUploadMapper;
import us.minitv.pcommerce.support.PCommerceBunny;
import us.minitv.pcommerce.support.PCommerceCrypto;
import us.minitv.pcommerce.support.PCommerceEnv;
import us.minitv.pcommerce.support.PCommerceUploadRejected;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bunny transcode callback, ported from
 * {@code app/modules/content/upload_service.apply_callback}.
 *
 * <p>A bad signature is audited before the request is rejected, so the audit
 * row must survive the failure: the transaction is committed even for
 * {@link PCommerceUploadRejected}.</p>
 */
@Service
public class PCommerceBunnyCallbackService {

    private static final Set<String> EPISODE_STATUSES =
            Set.of("not_uploaded", "transcoding", "transcode_failed", "in_review", "published", "unpublished");
    private static final Map<String, Set<String>> EPISODE_TRANSITIONS;

    static {
        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put("not_uploaded", Set.of("transcoding"));
        transitions.put("transcoding", Set.of("in_review", "transcode_failed"));
        transitions.put("transcode_failed", Set.of("transcoding"));
        transitions.put("in_review", Set.of("published", "not_uploaded"));
        transitions.put("published", Set.of("unpublished", "not_uploaded"));
        transitions.put("unpublished", Set.of("published", "not_uploaded"));
        EPISODE_TRANSITIONS = transitions;
    }

    private final PCommerceUploadMapper uploads;
    private final PCommerceContentService content;
    private final PCommerceAuditMapper audit;
    private final PCommerceBunny bunny;
    private final PCommerceEnv env;

    public PCommerceBunnyCallbackService(PCommerceUploadMapper uploads,
                                         PCommerceContentService content,
                                         PCommerceAuditMapper audit,
                                         PCommerceBunny bunny,
                                         PCommerceEnv env) {
        this.uploads = uploads;
        this.content = content;
        this.audit = audit;
        this.bunny = bunny;
        this.env = env;
    }

    @Transactional(noRollbackFor = PCommerceUploadRejected.class)
    public Map<String, Object> applyCallback(byte[] body, String signature, String videoId, String outcome) {
        String expected = PCommerceCrypto.hmacSha256Hex(env.bunnyCallbackSecret(), body);
        if (!PCommerceCrypto.constantTimeEquals(expected, signature)) {
            AuditLogRow rejection = new AuditLogRow();
            rejection.setActorType("system");
            rejection.setActorId(null);
            rejection.setAction("bunny_callback_rejected");
            rejection.setTargetType("media_upload");
            rejection.setTargetId(null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("video_id", videoId);
            rejection.setPayload(Json.write(payload));
            audit.insert(rejection);
            throw new PCommerceUploadRejected("bad signature");
        }
        MediaUploadRow upload = uploads.findByVideoId(videoId);
        if (upload == null) {
            throw new PCommerceUploadRejected("unknown video");
        }
        EpisodeRow episode = content.findEpisode(upload.getEpisodeId());
        if (episode == null) {
            throw new PCommerceUploadRejected("episode missing");
        }
        if ("completed".equals(upload.getStatus())) {
            return callbackResult(episode.getStatus(), videoId, true);
        }
        if ("failed".equals(outcome)) {
            if ("transcoding".equals(episode.getStatus())) {
                transitionEpisode(episode, "transcode_failed");
            }
            uploads.updateUploadStatus(upload.getId(), "failed");
        } else {
            if ("transcoding".equals(episode.getStatus())) {
                transitionEpisode(episode, "in_review");
            }
            uploads.updateUploadStatus(upload.getId(), "completed");
            // Real Bunny: read the transcoded length; a metadata hiccup must not
            // fail the callback, the sync script fills it later.
            if (bunny.live()) {
                int length = 0;
                try {
                    Map<String, Object> remote = bunny.streamVideo(videoId);
                    Object raw = remote.get("length");
                    if (raw instanceof Number number) {
                        length = number.intValue();
                    } else if (raw != null) {
                        length = Integer.parseInt(raw.toString().trim());
                    }
                } catch (RuntimeException ex) {
                    length = 0;
                }
                if (length > 0) {
                    uploads.updateEpisodeDuration(episode.getId(), length);
                }
            }
        }
        return callbackResult(episode.getStatus(), videoId, false);
    }

    private void transitionEpisode(EpisodeRow episode, String toStatus) {
        if (!EPISODE_STATUSES.contains(toStatus)) {
            throw new IllegalStateException("illegal episode transition: "
                    + episode.getStatus() + " -> " + toStatus);
        }
        Set<String> allowed = EPISODE_TRANSITIONS.getOrDefault(episode.getStatus(), Set.of());
        if (!allowed.contains(toStatus)) {
            throw new IllegalStateException("illegal episode transition: "
                    + episode.getStatus() + " -> " + toStatus);
        }
        uploads.updateEpisodeStatus(episode.getId(), toStatus);
        episode.setStatus(toStatus);
    }

    private static Map<String, Object> callbackResult(String status, String videoId, boolean replayed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("replayed", replayed);
        out.put("video_id", videoId);
        return out;
    }
}
