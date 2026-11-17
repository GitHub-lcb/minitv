package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Plan catalog, global billing mode and price changes. */
@RestController
public class AFFlowOpsBillingController {

    private final AFFlowOpsBillingService billing;

    public AFFlowOpsBillingController(AFFlowOpsBillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/admin/v1/plans")
    public Map<String, Object> adminPlans(@CurrentStaff StaffContext staff) {
        return billing.adminPlans();
    }

    @GetMapping("/admin/v1/billing/settings")
    public Map<String, Object> readBillingSettings(@CurrentStaff StaffContext staff) {
        return billing.readBillingSettings();
    }

    @PostMapping("/admin/v1/billing/settings")
    public Map<String, Object> updateBillingSettings(@CurrentStaff StaffContext staff,
                                                     @RequestBody AFFlowOpsBodies.BillingModeIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return billing.updateBillingSettings(body == null ? null : body.billing_mode(),
                staff.user().getId());
    }

    @PostMapping("/admin/v1/plans/{code}/price")
    public Map<String, Object> setPrice(@CurrentStaff StaffContext staff,
                                        @PathVariable("code") String code,
                                        @RequestBody AFFlowOpsBodies.PriceIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return billing.changePrice(code, body, staff.user().getId());
    }
}
