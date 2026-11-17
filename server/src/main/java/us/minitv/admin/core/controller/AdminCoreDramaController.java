package us.minitv.admin.core.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreContentService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** /admin/v1/dramas + /admin/v1/search - ops drama management and the top search box. */
@RestController
public class AdminCoreDramaController {

    public record DramaCreateIn(String title) {
    }

    private final AdminCoreContentService content;

    public AdminCoreDramaController(AdminCoreContentService content) {
        this.content = content;
    }

    @GetMapping("/admin/v1/dramas")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "tag_id", required = false) Long tagId,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return content.listDramas(status, q, tagId, page, pageSize);
    }

    @PostMapping("/admin/v1/dramas")
    public ResponseEntity<Map<String, Object>> create(@CurrentStaff StaffContext ctx,
                                                      @RequestBody DramaCreateIn body) {
        AdminCoreSupport.requireContentWriter(ctx.user());
        Map<String, Object> created = content.createDrama(ctx.user(), body.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/admin/v1/dramas/{drama_id}")
    public Map<String, Object> get(@CurrentStaff StaffContext ctx,
                                   @PathVariable("drama_id") long dramaId) {
        return content.getDrama(dramaId);
    }

    @PatchMapping("/admin/v1/dramas/{drama_id}")
    public Map<String, Object> update(@CurrentStaff StaffContext ctx,
                                      @PathVariable("drama_id") long dramaId,
                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireContentWriter(ctx.user());
        return content.updateDrama(ctx.user(), dramaId, body);
    }

    @GetMapping("/admin/v1/search")
    public Map<String, Object> search(@CurrentStaff StaffContext ctx,
                                      @RequestParam("q") String q) {
        return content.search(q);
    }
}
