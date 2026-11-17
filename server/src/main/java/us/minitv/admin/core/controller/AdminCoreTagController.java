package us.minitv.admin.core.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreContentService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** /admin/v1/tags - the tag library. */
@RestController
public class AdminCoreTagController {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TagIn(String nameEn, String nameZh, String category, String status) {
    }

    private final AdminCoreContentService content;

    public AdminCoreTagController(AdminCoreContentService content) {
        this.content = content;
    }

    @GetMapping("/admin/v1/tags")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx) {
        return content.listTags();
    }

    @PostMapping("/admin/v1/tags")
    public ResponseEntity<Map<String, Object>> create(@CurrentStaff StaffContext ctx,
                                                      @RequestBody TagIn body) {
        AdminCoreSupport.requireContentWriter(ctx.user());
        Map<String, Object> created = content.createTag(ctx.user(), body.nameEn(), body.nameZh(),
                body.category(), body.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/admin/v1/tags/{tag_id}")
    public Map<String, Object> update(@CurrentStaff StaffContext ctx,
                                      @PathVariable("tag_id") long tagId,
                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireContentWriter(ctx.user());
        return content.updateTag(ctx.user(), tagId, body);
    }

    @DeleteMapping("/admin/v1/tags/{tag_id}")
    public Map<String, Object> delete(@CurrentStaff StaffContext ctx,
                                      @PathVariable("tag_id") long tagId) {
        AdminCoreSupport.requireContentWriter(ctx.user());
        return content.deleteTag(ctx.user(), tagId);
    }
}
