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
import us.minitv.admin.core.service.AdminCoreSupplierService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** /admin/v1/suppliers - supplier tenants, their login account and revenue share. */
@RestController
public class AdminCoreSupplierController {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SupplierCreateIn(String name, String contactName, String contactEmail,
                                   Integer revenueShareBps, String note) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusIn(String status, String reason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShareIn(Integer shareBps, String note) {
    }

    private final AdminCoreSupplierService suppliers;

    public AdminCoreSupplierController(AdminCoreSupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping("/admin/v1/suppliers")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "status", required = false) String status) {
        // Readable by every staff role (ops needs it when reviewing).
        return suppliers.list(q, status);
    }

    @PostMapping("/admin/v1/suppliers")
    public ResponseEntity<Map<String, Object>> create(@CurrentStaff StaffContext ctx,
                                                      @RequestBody SupplierCreateIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        Map<String, Object> created = suppliers.create(ctx.user(), body.name(), body.contactName(),
                body.contactEmail(), body.revenueShareBps(), body.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/admin/v1/suppliers/{supplier_id}")
    public Map<String, Object> update(@CurrentStaff StaffContext ctx,
                                      @PathVariable("supplier_id") long supplierId,
                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return suppliers.update(ctx.user(), supplierId, body);
    }

    @PostMapping("/admin/v1/suppliers/{supplier_id}/status")
    public Map<String, Object> setStatus(@CurrentStaff StaffContext ctx,
                                         @PathVariable("supplier_id") long supplierId,
                                         @RequestBody StatusIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return suppliers.setStatus(ctx.user(), supplierId, body.status(), body.reason());
    }

    @PostMapping("/admin/v1/suppliers/{supplier_id}/share")
    public Map<String, Object> setShare(@CurrentStaff StaffContext ctx,
                                        @PathVariable("supplier_id") long supplierId,
                                        @RequestBody ShareIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return suppliers.setShare(ctx.user(), supplierId, body.shareBps(), body.note());
    }

    @GetMapping("/admin/v1/suppliers/{supplier_id}/share")
    public Map<String, Object> getShare(@CurrentStaff StaffContext ctx,
                                        @PathVariable("supplier_id") long supplierId) {
        return suppliers.getShare(supplierId);
    }
}
