package us.minitv.admin.flow.content;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.flow.content.mapper.AFContentAuditMapper;
import us.minitv.admin.flow.content.mapper.AFContentEpisodeMapper;
import us.minitv.admin.flow.content.mapper.AFContentMediaUploadMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.MediaUploadRow;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct-to-Bunny upload flow, ported from
 * {@code backend/app/modules/content/upload_service.py} plus the transcode
 * reconcile helpers and the fake-mode dev TUS receiver from
 * {@code backend/app/modules/ops/flow_router.py}.
 *
 * <p>Video bytes never pass through the API: the endpoint only creates a Bunny
 * video object (or a fake one), hands the browser TUS authorization headers and
 * later follows the remote object's status.</p>
 */
@Service
public class AFContentUploadService {

    static final long MAX_BYTES = 200L * 1024 * 1024;
    static final Set<String> ALLOWED_MIME = Set.of("video/mp4", "video/quicktime");

    /** Bunny Stream VideoModelStatus values, commented in the Python source. */
    private static final int REMOTE_FINISHED = 4;
    private static final int REMOTE_TRANSCODING = 3;
    private static final int REMOTE_FAILED = 5;
    private static final long REMOTE_STALLED_AFTER_SECONDS = 3600;

    private static final double LIBRARY_CACHE_SECONDS = 30.0;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Map<String, JsonNode> libraryCache = Map.of();
    private static long libraryFetchedAtNanos = 0L;

    private final AFContentEpisodeMapper episodes;
    private final AFContentMediaUploadMapper uploads;
    private final AFContentAuditMapper audits;
    private final AFContentBunny bunny;

    public AFContentUploadService(AFContentEpisodeMapper episodes,
                                  AFContentMediaUploadMapper uploads,
                                  AFContentAuditMapper audits,
                                  AFContentBunny bunny) {
        this.episodes = episodes;
        this.uploads = uploads;
        this.audits = audits;
        this.bunny = bunny;
    }

    // ------------------------------------------------------------------
    // upload-session / upload-headers
    // ------------------------------------------------------------------

    /** {@code create_upload_session}: issues a Bunny video id and TUS authorization. */
    @Transactional
    public Map<String, Object> createUploadSession(EpisodeRow episode, AFContentAdminDtos.UploadIn body) {
        String mime = body.mime();
        if (!ALLOWED_MIME.contains(mime)) {
            throw ApiException.unprocessable("only MP4 or MOV is accepted");
        }
        long sizeBytes = body.sizeBytes() == null ? 0L : body.sizeBytes();
        if (sizeBytes > MAX_BYTES) {
            throw ApiException.unprocessable("file exceeds 200MB");
        }
        int width = body.width() == null ? 0 : body.width();
        int height = body.height() == null ? 0 : body.height();
        if (width < 0 || height < 0) {
            throw ApiException.unprocessable("invalid video dimensions");
        }
        if ("transcoding".equals(episode.getStatus())) {
            throw ApiException.unprocessable("upload already in progress");
        }
        String previousVideoId = safe(episode.getBunnyVideoId());
        try {
            // _reset_for_replace: a replacement re-opens the row before the swap.
            if (Set.of("in_review", "published", "unpublished").contains(episode.getStatus())) {
                applyEpisodeTransition(episode, "not_uploaded");
                episode.setBunnyVideoId("");
                episodes.updateBunnyVideoId(episode.getId(), "");
            }
            if ("transcode_failed".equals(episode.getStatus()) || "not_uploaded".equals(episode.getStatus())) {
                applyEpisodeTransition(episode, "transcoding");
            } else {
                throw ApiException.unprocessable("cannot upload from status " + episode.getStatus());
            }
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw ApiException.unprocessable(exc.getMessage());
        }

        // Orientation stays metadata only: landscape uploads are allowed.
        boolean vertical = width > 0 && height > 0 ? height > width : true;
        String videoId;
        try {
            videoId = bunny.live() ? bunny.createStreamVideo(body.filename()) : randomTokenHex();
        } catch (AFContentBunny.BunnyError exc) {
            throw new ApiException(502, "bunny upstream: " + exc.getMessage());
        }

        MediaUploadRow upload = new MediaUploadRow();
        upload.setEpisodeId(episode.getId());
        upload.setVideoId(videoId);
        upload.setFilename(body.filename());
        upload.setMime(mime);
        upload.setSizeBytes(sizeBytes);
        upload.setVertical(vertical);
        upload.setStatus("created");
        uploads.insert(upload);

        int durationSeconds = body.durationSeconds() == null ? 0 : body.durationSeconds();
        int duration = currentDuration(episode);
        if (durationSeconds > 0) {
            duration = durationSeconds;
        } else if (!previousVideoId.isEmpty()) {
            // A replacement length is unknown: the old duration must not survive the swap.
            duration = 0;
        }
        episodes.updateUploadFields(episode.getId(), videoId, mime, sizeBytes, width, height, duration);
        episode.setBunnyVideoId(videoId);
        episode.setMime(mime);
        episode.setSizeBytes(sizeBytes);
        episode.setWidth(width);
        episode.setHeight(height);
        episode.setDurationSeconds(duration);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("video_id", videoId);
        if (bunny.live()) {
            result.put("upload_url", AFContentBunny.STREAM_TUS_ENDPOINT);
            result.put("headers", bunny.tusHeaders(videoId, 3600));
        } else {
            result.put("upload_url", "/admin/v1/dev-tus/" + videoId);
            result.put("headers", fakeTusHeaders(videoId));
        }
        result.put("episode_id", episode.getId());
        result.put("status", episode.getStatus());
        return result;
    }

    /** {@code episode_upload_headers}: re-mint TUS auth for an in-flight upload. */
    public Map<String, Object> episodeUploadHeaders(EpisodeRow episode) {
        if (!"transcoding".equals(episode.getStatus())) {
            throw new ApiException(409, ApiException.detail("upload_not_resumable",
                    "分集当前是 " + episode.getStatus() + "，只有 transcoding 状态可以续传"));
        }
        String videoId = safe(episode.getBunnyVideoId());
        MediaUploadRow upload = uploads.findLatestByEpisode(episode.getId(), videoId);
        if (upload == null || !"created".equals(upload.getStatus()) || videoId.isEmpty()) {
            throw new ApiException(409, ApiException.detail("upload_not_resumable", "没有可续传的上传会话"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("video_id", videoId);
        if (bunny.live()) {
            result.put("upload_url", AFContentBunny.STREAM_TUS_ENDPOINT);
            result.put("headers", bunny.tusHeaders(videoId, 3600));
        } else {
            result.put("upload_url", "/admin/v1/dev-tus/" + videoId);
            result.put("headers", fakeTusHeaders(videoId));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // sync-status / upload-failed
    // ------------------------------------------------------------------

    /** {@code sync_episode_status}: poll Bunny once and advance a transcoding row. */
    @Transactional
    public Map<String, Object> syncStatus(EpisodeRow episode) {
        String videoId = safe(episode.getBunnyVideoId());
        if (videoId.isEmpty()) {
            throw new ApiException(409, "episode has no video");
        }
        JsonNode remote = null;
        String source = "fake";
        if (bunny.live()) {
            source = "bunny";
            if ("transcoding".equals(episode.getStatus()) || currentDuration(episode) == 0) {
                try {
                    remote = bunny.streamVideo(videoId);
                } catch (AFContentBunny.BunnyError exc) {
                    throw new ApiException(502, "bunny upstream: " + exc.getMessage());
                }
            }
        }
        Map<String, Object> applied;
        try {
            applied = applyRemoteVideo(episode, remote);
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw new ApiException(409, exc.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("episode_id", episode.getId());
        result.put("status", applied.get("status"));
        result.put("remote_status", applied.get("remote_status"));
        result.put("duration_seconds", applied.get("duration_seconds"));
        result.put("source", source);
        return result;
    }

    /** {@code upload_failed}: report a browser-side failure so the row is retryable. */
    @Transactional
    public Map<String, Object> uploadFailed(EpisodeRow episode, AFContentAdminDtos.UploadFailedIn body) {
        AFContentAdminDtos.UploadFailedIn payload =
                body == null ? new AFContentAdminDtos.UploadFailedIn("", "") : body;
        String reason = payload.reason() == null ? "" : payload.reason();
        String payloadVideoId = payload.videoId() == null ? "" : payload.videoId();
        if (!"transcoding".equals(episode.getStatus())) {
            return uploadFailedResult(episode, reason, false, null);
        }
        // A browser error after the bytes landed must not stamp a failed transcode.
        String videoId = payloadVideoId.isEmpty() ? safe(episode.getBunnyVideoId()) : payloadVideoId;
        if (bunny.live() && !videoId.isEmpty()) {
            JsonNode remote = null;
            try {
                remote = bunny.streamVideo(videoId);
            } catch (AFContentBunny.BunnyError ignored) {
                remote = null;
            }
            if (remote != null && remoteHasBytes(remote)) {
                try {
                    applyRemoteVideo(episode, remote);
                } catch (AFContentTransitions.IllegalTransition exc) {
                    throw new ApiException(409, exc.getMessage());
                }
                return uploadFailedResult(episode, reason, !"transcoding".equals(episode.getStatus()),
                        "bytes already at bunny");
            }
        }
        try {
            applyEpisodeTransition(episode, "transcode_failed");
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw new ApiException(409, exc.getMessage());
        }
        MediaUploadRow upload = uploads.findLatestByEpisode(episode.getId(), videoId);
        if (upload != null) {
            uploads.updateStatus(upload.getId(), "failed");
        }
        return uploadFailedResult(episode, reason, true, null);
    }

    private Map<String, Object> uploadFailedResult(EpisodeRow episode, String reason, boolean updated, String ignored) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("episode_id", episode.getId());
        result.put("status", episode.getStatus());
        result.put("updated", updated);
        if (ignored != null) {
            result.put("reason", reason);
            result.put("ignored", ignored);
        } else {
            result.put("reason", reason);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Remote-video reconciliation (shared with list_episodes + background job)
    // ------------------------------------------------------------------

    /** {@code apply_remote_video}: advance one transcoding episode from a Bunny object. */
    @Transactional
    public Map<String, Object> applyRemoteVideo(EpisodeRow episode, JsonNode remote) {
        Integer remoteStatus = null;
        boolean durationUpdated = false;
        if (remote != null) {
            remoteStatus = intLoose(remote.get("status"));
            int length = intLoose(remote.get("length"));
            if (length > 0 && length != currentDuration(episode)) {
                episodes.updateDuration(episode.getId(), length);
                episode.setDurationSeconds(length);
                durationUpdated = true;
            }
        }
        boolean changed = durationUpdated;
        if ("transcoding".equals(episode.getStatus())) {
            String resolutions = remote == null ? "" : remote.path("availableResolutions").asText("");
            int length = remote == null ? 0 : intLoose(remote.get("length"));
            String target = null;
            if (remote == null || (remoteStatus != null && remoteStatus == REMOTE_FINISHED)) {
                target = "in_review";
            } else if (remoteStatus != null && remoteStatus == REMOTE_TRANSCODING
                    && !resolutions.isEmpty() && length > 0) {
                // Early play: at least one rendition is already watchable.
                target = "in_review";
            } else if (remoteStatus != null && remoteStatus >= REMOTE_FAILED) {
                target = "transcode_failed";
            } else if (remoteUploadStalled(remote)) {
                // Bunny kept an object the pipeline dropped: only a fresh session recovers it.
                target = "transcode_failed";
            }
            if (target != null) {
                applyEpisodeTransition(episode, target);
                MediaUploadRow upload = uploads.findLatestByEpisode(episode.getId(), safe(episode.getBunnyVideoId()));
                if (upload != null) {
                    uploads.updateStatus(upload.getId(), "in_review".equals(target) ? "completed" : "failed");
                }
                changed = true;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("episode_id", episode.getId());
        result.put("status", episode.getStatus());
        result.put("remote_status", remoteStatus);
        result.put("duration_seconds", currentDuration(episode));
        result.put("changed", changed);
        return result;
    }

    /** {@code reconcile_transcoding}: move every finished transcoding row forward. */
    @Transactional
    public Map<String, Object> reconcileTranscoding(Map<String, JsonNode> remoteByGuid) {
        List<EpisodeRow> rows = episodes.listByStatusWithVideo("transcoding");
        int advanced = 0;
        int failed = 0;
        int pending = 0;
        int missing = 0;
        for (EpisodeRow episode : rows) {
            JsonNode remote = remoteByGuid.get(safe(episode.getBunnyVideoId()));
            if (remote == null) {
                missing++;
                continue;
            }
            String before = episode.getStatus();
            Map<String, Object> applied;
            try {
                applied = applyRemoteVideo(episode, remote);
            } catch (AFContentTransitions.IllegalTransition exc) {
                pending++;
                continue;
            }
            String after = String.valueOf(applied.get("status"));
            if (after.equals(before)) {
                pending++;
            } else if ("transcode_failed".equals(after)) {
                failed++;
            } else {
                advanced++;
            }
        }
        if (advanced > 0 || failed > 0) {
            AuditLogRow log = new AuditLogRow();
            log.setActorType("system");
            log.setActorId(null);
            log.setAction("reconcile_transcoding");
            log.setTargetType("episode");
            log.setTargetId(null);
            log.setPayload(Json.write(orderedMap("advanced", advanced, "failed", failed,
                    "pending", pending, "missing", missing)));
            audits.insert(log);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("advanced", advanced);
        result.put("failed", failed);
        result.put("pending", pending);
        result.put("missing", missing);
        result.put("seen", rows.size());
        return result;
    }

    /** {@code _cached_library}: 30s in-process cache; a failed fetch keeps the last good copy. */
    public synchronized Map<String, JsonNode> cachedLibrary() {
        long now = System.nanoTime();
        Map<String, JsonNode> cached = libraryCache;
        if (!cached.isEmpty() && (now - libraryFetchedAtNanos) < (long) (LIBRARY_CACHE_SECONDS * 1_000_000_000L)) {
            return cached;
        }
        try {
            Map<String, JsonNode> fresh = bunny.listStreamVideos();
            libraryFetchedAtNanos = now;
            libraryCache = fresh;
            return fresh;
        } catch (AFContentBunny.BunnyError exc) {
            return cached;
        }
    }

    /** {@code _remote_upload_stalled}: a queued object the pipeline never picked up. */
    boolean remoteUploadStalled(JsonNode remote) {
        int status = intLoose(remote.get("status"));
        if (status != 0 && status != 1 && status != 2) {
            return false;
        }
        JsonNode uploadedNode = remote.get("dateUploaded");
        if (uploadedNode == null || uploadedNode.isNull()) {
            return false;
        }
        String uploaded = uploadedNode.asText("");
        if (uploaded.isEmpty()) {
            return false;
        }
        Instant uploadedAt = parseInstant(uploaded);
        if (uploadedAt == null) {
            return false;
        }
        if (Duration.between(uploadedAt, Instant.now()).getSeconds() <= REMOTE_STALLED_AFTER_SECONDS) {
            return false;
        }
        if (status == 0) {
            return intLoose(remote.get("storageSize")) == 0 && intLoose(remote.get("length")) == 0;
        }
        return intLoose(remote.get("encodeProgress")) == 0
                && remote.path("availableResolutions").asText("").isEmpty();
    }

    /** {@code _remote_has_bytes}: Bunny already holds the original. */
    boolean remoteHasBytes(JsonNode remote) {
        JsonNode original = remote.get("hasOriginal");
        if (truthy(original)) {
            return true;
        }
        if (intLoose(remote.get("storageSize")) > 0) {
            return true;
        }
        if (intLoose(remote.get("length")) > 0) {
            return true;
        }
        return intLoose(remote.get("status")) >= REMOTE_TRANSCODING;
    }

    // ------------------------------------------------------------------
    // Fake-mode dev TUS receiver
    // ------------------------------------------------------------------

    /** {@code _require_fake_mode}: the local receiver only exists in fake mode. */
    public void requireFakeMode() {
        if (bunny.live()) {
            throw new ApiException(404, "dev upload receiver is disabled in real mode");
        }
    }

    /** TUS create step: validates fake mode and the video id. */
    public void devTusCreate(String videoId) {
        requireFakeMode();
        requireUpload(videoId);
    }

    /** TUS HEAD step: returns the upload row so the controller can emit byte counts. */
    public MediaUploadRow devTusHead(String videoId) {
        requireFakeMode();
        return requireUpload(videoId);
    }

    /**
     * TUS data step: completes the upload at once and returns the new Upload-Offset.
     * Only single-shot uploads are supported, mirroring the Python receiver.
     */
    @Transactional
    public long devTusPatch(String videoId, String uploadOffsetHeader, long bodyLength) {
        requireFakeMode();
        MediaUploadRow upload = requireUpload(videoId);
        long stored = upload.getSizeBytes() == null ? 0L : upload.getSizeBytes();
        if ("completed".equals(upload.getStatus())) {
            return stored;
        }
        String offset = uploadOffsetHeader == null || uploadOffsetHeader.isEmpty() ? "0" : uploadOffsetHeader;
        if (!"0".equals(offset)) {
            throw new ApiException(409, "only single-shot uploads are supported");
        }
        EpisodeRow episode = episodes.findById(upload.getEpisodeId());
        if (episode == null) {
            throw new ApiException(404, "episode missing");
        }
        if ("transcoding".equals(episode.getStatus())) {
            try {
                applyEpisodeTransition(episode, "in_review");
            } catch (AFContentTransitions.IllegalTransition exc) {
                throw new ApiException(409, exc.getMessage());
            }
        }
        uploads.updateStatusAndSize(upload.getId(), "completed", bodyLength);
        episodes.updateSizeBytes(episode.getId(), bodyLength);
        return bodyLength;
    }

    private MediaUploadRow requireUpload(String videoId) {
        MediaUploadRow upload = uploads.findByVideoId(videoId);
        if (upload == null) {
            throw new ApiException(404, "unknown video id");
        }
        return upload;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Applies one legal episode transition to the row and the database. */
    void applyEpisodeTransition(EpisodeRow episode, String toStatus) {
        AFContentTransitions.requireEpisodeTransition(episode.getStatus(), toStatus);
        episodes.updateStatus(episode.getId(), toStatus);
        episode.setStatus(toStatus);
    }

    private Map<String, String> fakeTusHeaders(String videoId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationExpire", "300");
        headers.put("AuthorizationSignature", bunny.signCallback(videoId.getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    private static int currentDuration(EpisodeRow episode) {
        return episode.getDurationSeconds() == null ? 0 : episode.getDurationSeconds();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String randomTokenHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    static int intLoose(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        return !node.isEmpty();
    }

    /** Python {@code datetime.fromisoformat}; naive values are treated as UTC. */
    static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            map.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return map;
    }
}
