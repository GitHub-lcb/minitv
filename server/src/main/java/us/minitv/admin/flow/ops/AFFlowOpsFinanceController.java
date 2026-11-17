package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Finance console: orders and the coin ledger feed. */
@RestController
public class AFFlowOpsFinanceController {

    private final AFFlowOpsFinanceService finance;

    public AFFlowOpsFinanceController(AFFlowOpsFinanceService finance) {
        this.finance = finance;
    }

    @GetMapping("/admin/v1/finance/orders")
    public Map<String, Object> listOrders(@CurrentStaff StaffContext staff,
                                          @RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "billing_mode", required = false) String billingMode,
                                          @RequestParam(value = "page", defaultValue = "1") int page,
                                          @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return finance.financeOrders(q, status, billingMode, page, pageSize);
    }

    @GetMapping("/admin/v1/finance/orders/{order_no}")
    public Map<String, Object> orderDetail(@CurrentStaff StaffContext staff,
                                           @PathVariable("order_no") String orderNo) {
        return finance.financeOrderDetail(orderNo);
    }

    @GetMapping("/admin/v1/finance/ledger")
    public Map<String, Object> ledger(@CurrentStaff StaffContext staff) {
        return finance.financeLedger();
    }
}
