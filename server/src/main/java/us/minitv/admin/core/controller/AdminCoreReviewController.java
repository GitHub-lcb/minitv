package us.minitv.admin.core.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreReviewService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.List;
import java.util.Map;

/** /admin/v1/reviews - supplier review queue. */
@RestController
public class AdminCoreReviewController {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionIn(String decision, String note) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchIn(List<Long> dramaIds, String decision, String note) {
    }

    private final AdminCoreReviewService reviews;

    public AdminCoreReviewController(AdminCoreReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/admin/v1/reviews")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "state", required = false) String state,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        AdminCoreSupport.requireReviewer(ctx.user());
        return reviews.listReviews(state, q, page, pageSize);
    }

    @PostMapping("/admin/v1/reviews/batch")
    public Map<String, Object> batch(@CurrentStaff StaffContext ctx,
                                     @RequestBody BatchIn body) {
        AdminCoreSupport.requireReviewer(ctx.user());
        return reviews.batchDecision(ctx.user(), body.dramaIds(), body.decision(), body.note());
    }

    @PostMapping("/admin/v1/reviews/{drama_id}/decision")
    public Map<String, Object> decision(@CurrentStaff StaffContext ctx,
                                        @PathVariable("drama_id") long dramaId,
                                        @RequestBody DecisionIn body) {
        AdminCoreSupport.requireReviewer(ctx.user());
        return reviews.oneDecision(ctx.user(), dramaId, body.decision(), body.note());
    }

    @PostMapping("/admin/v1/reviews/{drama_id}/publish")
    public Map<String, Object> publish(@CurrentStaff StaffContext ctx,
                                       @PathVariable("drama_id") long dramaId) {
        AdminCoreSupport.requireReviewer(ctx.user());
        return reviews.publish(ctx.user(), dramaId);
    }
}
