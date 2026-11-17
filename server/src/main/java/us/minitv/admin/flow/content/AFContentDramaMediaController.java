package us.minitv.admin.flow.content;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.io.IOException;
import java.util.Map;

/**
 * Drama-level admin flow endpoints: cover assets, status changes, scheduling,
 * irreversible delete and the admin preview payload.
 */
@RestController
public class AFContentDramaMediaController {

    private final AFContentMediaService mediaService;
    private final AFContentPublishService publishService;
    private final AFContentPreviewService previewService;

    public AFContentDramaMediaController(AFContentMediaService mediaService,
                                         AFContentPublishService publishService,
                                         AFContentPreviewService previewService) {
        this.mediaService = mediaService;
        this.publishService = publishService;
        this.previewService = previewService;
    }

    @PostMapping("/admin/v1/dramas/{drama_id}/cover")
    public Map<String, Object> uploadCover(@CurrentStaff StaffContext staff,
                                           @PathVariable("drama_id") long dramaId,
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
        return mediaService.uploadCover(dramaId, data);
    }

    @DeleteMapping("/admin/v1/dramas/{drama_id}/cover")
    public Map<String, Object> deleteCover(@CurrentStaff StaffContext staff,
                                           @PathVariable("drama_id") long dramaId) {
        AFContentGuards.requireContentWriter(staff);
        return mediaService.deleteCover(dramaId);
    }

    @PostMapping("/admin/v1/dramas/{drama_id}/status")
    public Map<String, Object> dramaStatus(@CurrentStaff StaffContext staff,
                                           @PathVariable("drama_id") long dramaId,
                                           @Valid @RequestBody AFContentAdminDtos.StatusIn body) {
        AFContentGuards.requireContentWriter(staff);
        return publishService.changeDramaStatus(dramaId, body.toStatus(), staff.user().getId());
    }

    @PostMapping("/admin/v1/dramas/{drama_id}/schedule")
    public Map<String, Object> dramaSchedule(@CurrentStaff StaffContext staff,
                                             @PathVariable("drama_id") long dramaId,
                                             @Valid @RequestBody AFContentAdminDtos.ScheduleIn body) {
        AFContentGuards.requireContentWriter(staff);
        return publishService.schedulePublish(dramaId, body.at(), staff.user().getId());
    }

    @PostMapping("/admin/v1/schedules/apply")
    public Map<String, Object> applySchedules(@CurrentStaff StaffContext staff) {
        AFContentGuards.requireContentWriter(staff);
        return publishService.applyDueSchedules();
    }

    @PostMapping("/admin/v1/dramas/{drama_id}/delete")
    public Map<String, Object> deleteDrama(@CurrentStaff StaffContext staff,
                                           @PathVariable("drama_id") long dramaId,
                                           @RequestBody AFContentAdminDtos.DeleteIn body) {
        AFContentGuards.requireAdminRole(staff);
        return publishService.deleteDrama(dramaId, body != null && body.confirm(), staff.user().getId());
    }

    @GetMapping("/admin/v1/dramas/{drama_id}/preview")
    public Map<String, Object> preview(@CurrentStaff StaffContext staff,
                                       @PathVariable("drama_id") long dramaId,
                                       @RequestParam(value = "episode_id", required = false) Long episodeId) {
        // require_admin only: any staff role may open the admin preview.
        return previewService.preview(dramaId, episodeId);
    }
}
