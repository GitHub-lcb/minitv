package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Comment moderation queue and the auto-moderation policy. */
@RestController
public class AFFlowOpsModerationController {

    private final AFFlowOpsModerationService moderation;

    public AFFlowOpsModerationController(AFFlowOpsModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/admin/v1/comments")
    public Map<String, Object> listComments(@CurrentStaff StaffContext staff,
                                            @RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "drama_id", required = false) Long dramaId,
                                            @RequestParam(value = "episode_id", required = false) Long episodeId,
                                            @RequestParam(value = "q", required = false) String q,
                                            @RequestParam(value = "page", defaultValue = "1") int page,
                                            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return moderation.listComments(status, dramaId, episodeId, q, page, pageSize);
    }

    @GetMapping("/admin/v1/moderation/settings")
    public Map<String, Object> readSettings(@CurrentStaff StaffContext staff) {
        return moderation.readSettings();
    }

    @PostMapping("/admin/v1/moderation/settings")
    public Map<String, Object> updateSettings(@CurrentStaff StaffContext staff,
                                              @RequestBody AFFlowOpsBodies.ModerationSettingsIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return moderation.updateSettings(body, staff.user().getId());
    }

    @PostMapping("/admin/v1/moderation/test")
    public Map<String, Object> testModeration(@CurrentStaff StaffContext staff,
                                              @RequestBody AFFlowOpsBodies.ModerationTestIn body) {
        return moderation.testModeration(body);
    }

    @PostMapping("/admin/v1/comments/{comment_id}")
    public Map<String, Object> moderateComment(@CurrentStaff StaffContext staff,
                                               @PathVariable("comment_id") long commentId,
                                               @RequestBody AFFlowOpsBodies.CommentModIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return moderation.moderateComment(commentId, body);
    }
}
