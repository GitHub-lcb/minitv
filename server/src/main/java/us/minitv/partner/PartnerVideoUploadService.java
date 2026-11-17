package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.MediaUploadRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.partner.mapper.PartnerDramaMapper;
import us.minitv.partner.mapper.PartnerEpisodeMapper;
import us.minitv.partner.mapper.PartnerMediaUploadMapper;
import us.minitv.security.Tokens;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct-to-Bunny upload sessions plus the fake-mode TUS receiver, ported from
 * {@code content/upload_service.py} and the partner upload routes.
 *
 * <p>The API stores metadata only and never reads video bytes; fake mode's
 * receiver lives under {@code /partner/v1/dev-tus} so the path guard stays
 * intact.
 */
@Service
public class PartnerVideoUploadService {

    private static final int TUS_TTL_SECONDS = 3600;
    private static final Set<String> REPLACEABLE_STATUSES = Set.of("in_review", "published", "unpublished");

    private final PartnerEpisodeMapper episodes;
    private final PartnerDramaMapper dramas;
    private final PartnerMediaUploadMapper uploads;
    private final PartnerContentSupport support;
    private final PartnerBunny bunny;

    public PartnerVideoUploadService(PartnerEpisodeMapper episodes,
                                     PartnerDramaMapper dramas,
                                     PartnerMediaUploadMapper uploads,
                                     PartnerContentSupport support,
                                     PartnerBunny bunny) {
        this.episodes = episodes;
        this.dramas = dramas;
        this.uploads = uploads;
        this.support = support;
        this.bunny = bunny;
    }

    // ---- upload session ----

    @Transactional
    public Map<String, Object> createSession(AdminUserRow user, long episodeId, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        String filename = PartnerBody.requiredString(body, "filename", 1, 200);
        String mime = PartnerBody.requiredString(body, "mime", 1, 64);
        int sizeBytes = PartnerBody.requiredInt(body, "size_bytes", 1, null);
        int width = PartnerBody.intOr(body, "width", 0, 0, null);
        int height = PartnerBody.intOr(body, "height", 0, 0, null);
        int durationSeconds = PartnerBody.intOr(body, "duration_seconds", 0, 0, null);

        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        DramaRow drama = dramas.findById(episode.getDramaId());
        support.requireEditable(drama);

        try {
            if (!PartnerLimits.ALLOWED_MIME.contains(mime)) {
                throw new PartnerUploadRejected("only MP4 or MOV is accepted");
            }
            if (sizeBytes > PartnerLimits.MAX_UPLOAD_BYTES) {
                throw new PartnerUploadRejected("file exceeds 200MB");
            }
            // Orientation is deliberately unconstrained; only impossible dimensions are rejected.
            if (width < 0 || height < 0) {
                throw new PartnerUploadRejected("invalid video dimensions");
            }
            if ("transcoding".equals(episode.getStatus())) {
                throw new PartnerUploadRejected("upload already in progress");
            }
            String previousVideoId = episode.getBunnyVideoId();
            if (REPLACEABLE_STATUSES.contains(episode.getStatus())) {
                PartnerTransitions.requireEpisode(episode.getStatus(), "not_uploaded");
                episodes.resetForReplace(episode.getId());
                episode.setStatus("not_uploaded");
                episode.setBunnyVideoId("");
            }
            if ("transcode_failed".equals(episode.getStatus()) || "not_uploaded".equals(episode.getStatus())) {
                PartnerTransitions.requireEpisode(episode.getStatus(), "transcoding");
            } else {
                throw new PartnerUploadRejected("cannot upload from status " + episode.getStatus());
            }

            // ``vertical`` stays as metadata only (analysis/reporting); it is not a gate.
            boolean vertical = (width > 0 && height > 0) ? height > width : true;
            String videoId;
            if (bunny.live()) {
                videoId = bunny.createStreamVideo(filename);
            } else {
                videoId = Tokens.hex(16);
            }

            MediaUploadRow upload = new MediaUploadRow();
            upload.setEpisodeId(episode.getId());
            upload.setVideoId(videoId);
            upload.setFilename(filename);
            upload.setMime(mime);
            upload.setSizeBytes((long) sizeBytes);
            upload.setVertical(vertical);
            upload.setStatus("created");
            uploads.insertUpload(upload);

            int finalDuration = episode.getDurationSeconds() == null ? 0 : episode.getDurationSeconds();
            if (durationSeconds > 0) {
                // Browser-probed length: a useful placeholder until Bunny reports the authoritative one.
                finalDuration = durationSeconds;
            } else if (PartnerText.truthy(previousVideoId)) {
                // A replacement length is unknown; the old duration must not survive the swap.
                finalDuration = 0;
            }
            episodes.updateUploadFields(episode.getId(), videoId, mime, sizeBytes, width, height,
                    finalDuration, "transcoding");
            episode.setStatus("transcoding");
            episode.setBunnyVideoId(videoId);
            episode.setDurationSeconds(finalDuration);
            episode.setSizeBytes((long) sizeBytes);

            String uploadUrl;
            Map<String, String> headers;
            if (bunny.live()) {
                uploadUrl = PartnerBunny.STREAM_TUS_ENDPOINT;
                headers = bunny.tusHeaders(videoId, TUS_TTL_SECONDS);
            } else {
                uploadUrl = "/admin/v1/dev-tus/" + videoId;
                Map<String, String> fakeHeaders = new LinkedHashMap<>();
                fakeHeaders.put("AuthorizationExpire", "300");
                fakeHeaders.put("AuthorizationSignature",
                        bunny.signCallback(videoId.getBytes(StandardCharsets.UTF_8)));
                headers = fakeHeaders;
            }
            if (!bunny.live() && uploadUrl.startsWith("/admin/v1/")) {
                uploadUrl = uploadUrl.replace("/admin/v1/", "/partner/v1/");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("video_id", videoId);
            payload.put("upload_url", uploadUrl);
            payload.put("headers", headers);
            payload.put("episode_id", episode.getId());
            payload.put("status", episode.getStatus());
            return payload;
        } catch (PartnerUploadRejected ex) {
            throw PartnerErrors.unprocessable(ex.reason());
        } catch (PartnerBunnyException ex) {
            throw new ApiException(502, "bunny upstream: " + ex.getMessage());
        }
    }

    // ---- resumable upload helpers ----

    public Map<String, Object> uploadHeaders(AdminUserRow user, long episodeId) {
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        if (!"transcoding".equals(episode.getStatus())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("upload_not_resumable", "只有转码中的分集可以续传"));
        }
        MediaUploadRow upload = uploads.findLatestByEpisodeAndVideo(episode.getId(), episode.getBunnyVideoId());
        if (upload == null) {
            throw PartnerErrors.notFound("upload not found");
        }
        boolean live = bunny.live();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("episode_id", episode.getId());
        payload.put("video_id", episode.getBunnyVideoId());
        payload.put("upload_url", live ? PartnerBunny.STREAM_TUS_ENDPOINT : "");
        payload.put("headers", live ? bunny.tusHeaders(episode.getBunnyVideoId(), TUS_TTL_SECONDS) : Map.of());
        return payload;
    }

    @Transactional
    public Map<String, Object> uploadStatus(AdminUserRow user, long episodeId) {
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        MediaUploadRow upload = uploads.findLatestByEpisode(episode.getId());
        // A session that never received bytes would otherwise sit in "transcoding"
        // forever. 30 minutes is beyond a realistic 200MB upload on a slow link.
        if ("transcoding".equals(episode.getStatus())
                && upload != null
                && "created".equals(upload.getStatus())
                && upload.getCreatedAt() != null) {
            Duration age = Duration.between(upload.getCreatedAt(), Times.now());
            if (age.compareTo(Duration.ofMinutes(30)) > 0) {
                boolean hasBytes = false;
                if (bunny.live() && PartnerText.truthy(episode.getBunnyVideoId())) {
                    try {
                        JsonNode remote = bunny.streamVideo(episode.getBunnyVideoId());
                        hasBytes = remote.path("hasOriginal").asBoolean(false)
                                || remote.path("storageSize").asLong(0) > 0
                                || remote.path("length").asLong(0) > 0
                                || remote.path("status").asInt(0) >= 3
                                || remote.path("encodeProgress").asInt(0) > 0;
                    } catch (PartnerBunnyException ex) {
                        hasBytes = false;
                    }
                }
                if (!hasBytes) {
                    try {
                        support.transitionEpisode(episode, "transcode_failed");
                        uploads.updateStatus(upload.getId(), "failed");
                        upload.setStatus("failed");
                    } catch (PartnerInvalidTransition ex) {
                        // wrong source state: leave the row untouched, same as the Python rollback
                    }
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("episode_id", episode.getId());
        payload.put("episode_number", episode.getEpisodeNumber());
        payload.put("status", episode.getStatus());
        payload.put("duration_seconds", episode.getDurationSeconds());
        payload.put("size_bytes", episode.getSizeBytes());
        payload.put("video_id", episode.getBunnyVideoId());
        payload.put("upload_status", upload == null ? null : upload.getStatus());
        return payload;
    }

    /** Let the supplier release a dead upload session immediately. */
    @Transactional
    public Map<String, Object> uploadFailed(AdminUserRow user, long episodeId, JsonNode ignoredBody) {
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        DramaRow drama = dramas.findById(episode.getDramaId());
        support.requireEditable(drama);
        if (!"transcoding".equals(episode.getStatus())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("episode_id", episode.getId());
            payload.put("status", episode.getStatus());
            payload.put("updated", false);
            return payload;
        }
        String videoId = episode.getBunnyVideoId();
        if (bunny.live() && PartnerText.truthy(videoId)) {
            JsonNode remote = null;
            try {
                remote = bunny.streamVideo(videoId);
            } catch (PartnerBunnyException ex) {
                remote = null;
            }
            if (remote != null
                    && (remote.path("storageSize").asLong(0) > 0
                    || remote.path("length").asLong(0) > 0
                    || remote.path("status").asInt(0) >= 3
                    || remote.path("encodeProgress").asInt(0) > 0)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("episode_id", episode.getId());
                payload.put("status", episode.getStatus());
                payload.put("updated", false);
                payload.put("ignored", "bytes already at bunny");
                return payload;
            }
        }
        try {
            support.transitionEpisode(episode, "transcode_failed");
        } catch (PartnerInvalidTransition ex) {
            throw PartnerErrors.conflict(ex.getMessage());
        }
        MediaUploadRow upload = uploads.findLatestByEpisode(episode.getId());
        if (upload != null) {
            uploads.updateStatus(upload.getId(), "failed");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("episode_id", episode.getId());
        payload.put("status", episode.getStatus());
        payload.put("updated", true);
        return payload;
    }

    // ---- fake-mode TUS receiver ----

    public Map<String, String> devTusCreate(AdminUserRow user, String videoId) {
        requireFakeMode();
        ownUpload(user, videoId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Location", "/partner/v1/dev-tus/" + videoId);
        headers.put("Tus-Resumable", "1.0.0");
        return headers;
    }

    public Map<String, String> devTusHead(AdminUserRow user, String videoId) {
        requireFakeMode();
        MediaUploadRow upload = ownUpload(user, videoId);
        long offset = "completed".equals(upload.getStatus())
                ? (upload.getSizeBytes() == null ? 0L : upload.getSizeBytes())
                : 0L;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Upload-Offset", String.valueOf(offset));
        headers.put("Upload-Length", String.valueOf(upload.getSizeBytes() == null ? 0L : upload.getSizeBytes()));
        headers.put("Tus-Resumable", "1.0.0");
        return headers;
    }

    @Transactional
    public PartnerDevTusResult devTusPatch(AdminUserRow user, String videoId, String uploadOffsetHeader, byte[] body) {
        requireFakeMode();
        MediaUploadRow upload = ownUpload(user, videoId);
        Map<String, String> headers = new LinkedHashMap<>();
        if ("completed".equals(upload.getStatus())) {
            headers.put("Upload-Offset", String.valueOf(upload.getSizeBytes() == null ? 0L : upload.getSizeBytes()));
            headers.put("Tus-Resumable", "1.0.0");
            return new PartnerDevTusResult(204, headers);
        }
        String offset = uploadOffsetHeader == null || uploadOffsetHeader.isEmpty() ? "0" : uploadOffsetHeader;
        if (!"0".equals(offset)) {
            throw PartnerErrors.conflict("only single-shot uploads are supported");
        }
        byte[] bytes = body == null ? new byte[0] : body;
        EpisodeRow episode = episodes.findById(upload.getEpisodeId());
        if (episode == null) {
            throw PartnerErrors.notFound("episode missing");
        }
        if ("transcoding".equals(episode.getStatus())) {
            try {
                support.transitionEpisode(episode, "in_review");
            } catch (PartnerInvalidTransition ex) {
                throw PartnerErrors.conflict(ex.getMessage());
            }
        }
        uploads.updateStatusAndSize(upload.getId(), "completed", bytes.length);
        episodes.updateSizeBytes(episode.getId(), bytes.length);
        headers.put("Upload-Offset", String.valueOf(bytes.length));
        headers.put("Tus-Resumable", "1.0.0");
        return new PartnerDevTusResult(204, headers);
    }

    private void requireFakeMode() {
        if (bunny.live()) {
            throw PartnerErrors.notFound("dev upload receiver is disabled in real mode");
        }
    }

    private MediaUploadRow ownUpload(AdminUserRow user, String videoId) {
        MediaUploadRow upload = uploads.findOwnedByVideoId(videoId, user.getSupplierId());
        if (upload == null) {
            throw PartnerErrors.notFound("unknown video id");
        }
        return upload;
    }
}
