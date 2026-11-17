package us.minitv.admin.flow.ops;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.common.ApiException;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Dashboard aggregates plus the five most recent orders. */
@RestController
public class AFFlowOpsDashboardController {

    private final AFFlowOpsAnalyticsService analytics;
    private final AFFlowOpsFinanceService finance;

    public AFFlowOpsDashboardController(AFFlowOpsAnalyticsService analytics, AFFlowOpsFinanceService finance) {
        this.analytics = analytics;
        this.finance = finance;
    }

    @GetMapping("/admin/v1/dashboard")
    public Map<String, Object> dashboard(
            @CurrentStaff StaffContext staff,
            @RequestParam(value = "day", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        if (days < 1 || days > 90) {
            throw ApiException.unprocessable("Input should be between 1 and 90");
        }
        Map<String, Object> data = analytics.dashboard(day, days);
        Map<String, Object> orders = finance.financeOrders(null, null, null, 1, 5);
        data.put("recent_orders", orders.get("items"));
        return data;
    }
}
