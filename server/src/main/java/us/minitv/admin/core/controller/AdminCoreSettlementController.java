package us.minitv.admin.core.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreSettlementService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** /admin/v1/settlements - monthly supplier statements (finance/admin only). */
@RestController
public class AdminCoreSettlementController {

    public record GenerateIn(String month) {
    }

    public record StatusIn(String status, String note) {
    }

    private final AdminCoreSettlementService settlements;

    public AdminCoreSettlementController(AdminCoreSettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/admin/v1/settlements")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "month", required = false) String month,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        AdminCoreSupport.requireMoney(ctx.user());
        return settlements.list(month, status, page, pageSize);
    }

    @PostMapping("/admin/v1/settlements/generate")
    public Map<String, Object> generate(@CurrentStaff StaffContext ctx,
                                        @RequestBody GenerateIn body) {
        AdminCoreSupport.requireMoney(ctx.user());
        String month = body.month();
        if (month == null || !month.matches("^\\d{4}-\\d{2}$")) {
            throw us.minitv.common.ApiException.unprocessable(
                    "month: String should match pattern '^\\d{4}-\\d{2}$'");
        }
        return settlements.generate(ctx.user(), month);
    }

    @PostMapping("/admin/v1/settlements/{settlement_id}/status")
    public Map<String, Object> changeStatus(@CurrentStaff StaffContext ctx,
                                            @PathVariable("settlement_id") long settlementId,
                                            @RequestBody StatusIn body) {
        AdminCoreSupport.requireMoney(ctx.user());
        return settlements.changeStatus(ctx.user(), settlementId, body.status(), body.note());
    }
}
