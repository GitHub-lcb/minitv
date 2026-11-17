package us.minitv.admin.core.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
import us.minitv.admin.core.service.AdminCoreStaffService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** /admin/v1/staff - staff account management (super-admin only). */
@RestController
public class AdminCoreStaffController {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StaffCreateIn(String username, String displayName, String role, String password,
                                Long supplierId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StaffPasswordIn(String newPassword) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusIn(String status, String reason) {
    }

    private final AdminCoreStaffService staff;

    public AdminCoreStaffController(AdminCoreStaffService staff) {
        this.staff = staff;
    }

    @GetMapping("/admin/v1/staff")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "role", required = false) String role,
                                    @RequestParam(value = "status", required = false) String status) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return staff.list(q, role, status);
    }

    @PostMapping("/admin/v1/staff")
    public ResponseEntity<Map<String, Object>> create(@CurrentStaff StaffContext ctx,
                                                      @RequestBody StaffCreateIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        Map<String, Object> created = staff.create(ctx.user(), body.username(), body.displayName(),
                body.role(), body.password(), body.supplierId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/admin/v1/staff/{staff_id}")
    public Map<String, Object> update(@CurrentStaff StaffContext ctx,
                                      @PathVariable("staff_id") long staffId,
                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return staff.update(ctx.user(), staffId, body);
    }

    @PostMapping("/admin/v1/staff/{staff_id}/status")
    public Map<String, Object> setStatus(@CurrentStaff StaffContext ctx,
                                         @PathVariable("staff_id") long staffId,
                                         @RequestBody StatusIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return staff.setStatus(ctx.user(), staffId, body.status(), body.reason());
    }

    @PostMapping("/admin/v1/staff/{staff_id}/password")
    public ResponseEntity<Void> resetPassword(@CurrentStaff StaffContext ctx,
                                              @PathVariable("staff_id") long staffId,
                                              @RequestBody StaffPasswordIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        staff.resetPassword(ctx.user(), staffId, body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
