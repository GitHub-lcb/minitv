package us.minitv.admin.flow.content;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.MediaUploadRow;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Admin episode endpoints (create/list/edit, direct upload, publish, bulk,
 * delete, subtitles), ported from {@code backend/app/modules/ops/flow_router.py}.
 *
 * <p>Every route requires a platform staff session/API key; the finer
 * {@code require_content_writer} gate (ops|admin) is applied inside the
 * handlers exactly where Python applies it.</p>
 */
@RestController
public class AFContentEpisodeController {

    private final AFContentEpisodeService episodeService;
    private final AFContentUploadService uploadService;
    private final AFContentMediaService mediaService;

    public AFContentEpisodeController(AFContentEpisodeService episodeService,
                                      AFContentUploadService uploadService,
                                      AFContentMediaService mediaService) {
        this.episodeService = episodeService;
        this.uploadService = uploadService;
        this.mediaService = mediaService;
    }

    // ------------------------------------------------------------------
    // Episodes: create / list / edit
    // ------------------------------------------------------------------

    @PostMapping("/admin/v1/dramas/{drama_id}/episodes")
    public Map<String, Object> createEpisode(@CurrentStaff StaffContext staff,
                                             @PathVariable("drama_id") long dramaId,
                                             @Valid @RequestBody AFContentAdminDtos.EpisodeIn body) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.createEpisode(dramaId, body);
    }

    @PostMapping("/admin/v1/dramas/{drama_id}/episodes/bulk")
    public Map<String, Object> bulkEpisodes(@CurrentStaff StaffContext staff,
                                            @PathVariable("drama_id") long dramaId,
                                            @Valid @RequestBody AFContentAdminDtos.BulkIn body) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.bulkEpisodes(dramaId, body);
    }

    @GetMapping("/admin/v1/dramas/{drama_id}/episodes")
    public Map<String, Object> listEpisodes(@CurrentStaff StaffContext staff,
                                            @PathVariable("drama_id") long dramaId,
                                            @RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "q", required = false) String q,
                                            @RequestParam(value = "page", defaultValue = "1") String page,
                                            @RequestParam(value = "page_size", defaultValue = "20") String pageSize) {
        // list_episodes uses require_admin (any staff role), not require_content_writer.
        int pageNumber = parseBounded(page, "page", 1, null);
        int size = parseBounded(pageSize, "page_size", 1, 200);
        return episodeService.listEpisodes(dramaId, status, q, pageNumber, size);
    }

    @PatchMapping("/admin/v1/episodes/{episode_id}")
    public Map<String, Object> updateEpisode(@CurrentStaff StaffContext staff,
                                             @PathVariable("episode_id") long episodeId,
                                             @Valid @RequestBody AFContentAdminDtos.EpisodeUpdateIn body) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.updateEpisode(episodeId, body);
    }

    @DeleteMapping("/admin/v1/episodes/{episode_id}")
    public ResponseEntity<Void> deleteEpisode(@CurrentStaff StaffContext staff,
                                              @PathVariable("episode_id") long episodeId) {
        AFContentGuards.requireContentWriter(staff);
        episodeService.deleteEpisode(episodeId, staff.user().getId());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Upload flow
    // ------------------------------------------------------------------

    @GetMapping("/admin/v1/episodes/{episode_id}/preview-url")
    public Map<String, Object> previewUrl(@CurrentStaff StaffContext staff,
                                          @PathVariable("episode_id") long episodeId) {
        // require_admin (any staff role) — no content-writer gate on previews.
        return episodeService.previewUrl(episodeId);
    }

    @PostMapping("/admin/v1/episodes/{episode_id}/upload-session")
    public Map<String, Object> uploadSession(@CurrentStaff StaffContext staff,
                                             @PathVariable("episode_id") long episodeId,
                                             @Valid @RequestBody AFContentAdminDtos.UploadIn body) {
        AFContentGuards.requireContentWriter(staff);
        EpisodeRow episode = episodeService.requireEpisode(episodeId);
        return uploadService.createUploadSession(episode, body);
    }

    @PostMapping("/admin/v1/episodes/{episode_id}/upload-headers")
    public Map<String, Object> uploadHeaders(@CurrentStaff StaffContext staff,
                                             @PathVariable("episode_id") long episodeId) {
        AFContentGuards.requireContentWriter(staff);
        return uploadService.episodeUploadHeaders(episodeService.requireEpisode(episodeId));
    }

    @PostMapping("/admin/v1/episodes/{episode_id}/sync-status")
    public Map<String, Object> syncStatus(@CurrentStaff StaffContext staff,
                                          @PathVariable("episode_id") long episodeId) {
        AFContentGuards.requireContentWriter(staff);
        return uploadService.syncStatus(episodeService.requireEpisode(episodeId));
    }

    @PostMapping("/admin/v1/episodes/{episode_id}/upload-failed")
    public Map<String, Object> uploadFailed(@CurrentStaff StaffContext staff,
                                            @PathVariable("episode_id") long episodeId,
                                            @RequestBody(required = false) AFContentAdminDtos.UploadFailedIn body) {
        AFContentGuards.requireContentWriter(staff);
        return uploadService.uploadFailed(episodeService.requireEpisode(episodeId), body);
    }

    // ------------------------------------------------------------------
    // Fake-mode dev TUS receiver (mirrors Bunny's /tusupload handshake)
    // ------------------------------------------------------------------

    @PostMapping("/admin/v1/dev-tus/{video_id}")
    public ResponseEntity<Void> devTusCreate(@CurrentStaff StaffContext staff,
                                             @PathVariable("video_id") String videoId) {
        AFContentGuards.requireContentWriter(staff);
        uploadService.devTusCreate(videoId);
        return ResponseEntity.status(201)
                .header("Location", "/admin/v1/dev-tus/" + videoId)
                .header("Tus-Resumable", "1.0.0")
                .build();
    }

    @RequestMapping(value = "/admin/v1/dev-tus/{video_id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> devTusHead(@CurrentStaff StaffContext staff,
                                           @PathVariable("video_id") String videoId) {
        AFContentGuards.requireContentWriter(staff);
        MediaUploadRow upload = uploadService.devTusHead(videoId);
        long stored = upload.getSizeBytes() == null ? 0L : upload.getSizeBytes();
        long offset = "completed".equals(upload.getStatus()) ? stored : 0L;
        return ResponseEntity.ok()
                .header("Upload-Offset", String.valueOf(offset))
                .header("Upload-Length", String.valueOf(stored))
                .header("Tus-Resumable", "1.0.0")
                .build();
    }

    @PatchMapping("/admin/v1/dev-tus/{video_id}")
    public ResponseEntity<Void> devTusPatch(@CurrentStaff StaffContext staff,
                                            @PathVariable("video_id") String videoId,
                                            HttpServletRequest request) {
        AFContentGuards.requireContentWriter(staff);
        long bodyLength = countBody(request);
        long offset = uploadService.devTusPatch(videoId, request.getHeader("upload-offset"), bodyLength);
        return ResponseEntity.noContent()
                .header("Upload-Offset", String.valueOf(offset))
                .header("Tus-Resumable", "1.0.0")
                .build();
    }

    // ------------------------------------------------------------------
    // Publish / bulk status / subtitles
    // ------------------------------------------------------------------

    @PostMapping("/admin/v1/episodes/{episode_id}/publish")
    public Map<String, Object> publish(@CurrentStaff StaffContext staff,
                                       @PathVariable("episode_id") long episodeId) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.publishEpisode(episodeId);
    }

    @PostMapping("/admin/v1/episodes/{episode_id}/unpublish")
    public Map<String, Object> unpublish(@CurrentStaff StaffContext staff,
                                         @PathVariable("episode_id") long episodeId) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.unpublishEpisode(episodeId);
    }

    @PostMapping("/admin/v1/episodes/bulk-status")
    public Map<String, Object> bulkStatus(@CurrentStaff StaffContext staff,
                                          @Valid @RequestBody AFContentAdminDtos.EpisodeBulkStatusIn body) {
        AFContentGuards.requireContentWriter(staff);
        return episodeService.bulkStatus(body);
    }

    @PostMapping(value = "/admin/v1/episodes/{episode_id}/subtitle")
    public Map<String, Object> uploadSubtitle(@CurrentStaff StaffContext staff,
                                              @PathVariable("episode_id") long episodeId,
                                              @RequestParam(value = "file", required = false) MultipartFile file) {
        AFContentGuards.requireContentWriter(staff);
        if (file == null) {
            throw AFContentGuards.bodyValidation("file", "missing", "Field required");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException exc) {
            throw new IllegalStateException(exc);
        }
        return mediaService.uploadSubtitle(episodeId, data, file.getOriginalFilename());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int parseBounded(String raw, String name, int minimum, Integer maximum) {
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException exc) {
            throw AFContentGuards.queryValidation(name, "int_parsing",
                    "Input should be a valid integer, unable to parse string as an integer");
        }
        if (value < minimum) {
            throw AFContentGuards.queryValidation(name, "greater_than_equal",
                    "Input should be greater than or equal to " + minimum);
        }
        if (maximum != null && value > maximum) {
            throw AFContentGuards.queryValidation(name, "less_than_equal",
                    "Input should be less than or equal to " + maximum);
        }
        return value;
    }

    private static long countBody(HttpServletRequest request) {
        try (InputStream input = request.getInputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
            }
            return total;
        } catch (IOException exc) {
            throw new IllegalStateException(exc);
        }
    }
}
