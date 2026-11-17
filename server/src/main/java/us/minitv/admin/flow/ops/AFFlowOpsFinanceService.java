package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import us.minitv.billing.row.OrderRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.account.row.UserRow;
import us.minitv.wallet.row.CoinLedgerRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Order ledger for the finance console (/admin/v1/finance/...). */
@Service
public class AFFlowOpsFinanceService {

    static final Set<String> ORDER_STATUSES = Set.of("created", "paid", "failed", "refunded");
    static final Set<String> ORDER_BILLING_MODES = Set.of("subscription", "one_time");

    private final AFFlowOpsFinanceMapper finance;
    private final AFFlowOpsUserMapper users;
    private final AFFlowOpsBillingMapper billing;
    private final AFFlowOpsBillingService billingService;

    public AFFlowOpsFinanceService(AFFlowOpsFinanceMapper finance, AFFlowOpsUserMapper users,
                                   AFFlowOpsBillingMapper billing, AFFlowOpsBillingService billingService) {
        this.finance = finance;
        this.users = users;
        this.billing = billing;
        this.billingService = billingService;
    }

    public Map<String, Object> financeOrders(String q, String status, String billingMode, int page, int pageSize) {
        if (page < 1) {
            throw ApiException.unprocessable("Input should be greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw ApiException.unprocessable("Input should be between 1 and 100");
        }
        String needle = q == null ? "" : q.strip();
        String like = needle.isEmpty() ? null : "%" + needle + "%";
        String needleFilter = like == null ? null : needle;
        String statusFilter = status != null && ORDER_STATUSES.contains(status) ? status : null;
        String billingFilter = billingMode != null && ORDER_BILLING_MODES.contains(billingMode) ? billingMode : null;

        long total = finance.countOrders(needleFilter, like, statusFilter, billingFilter);
        List<OrderRow> rows = finance.searchOrders(needleFilter, like, statusFilter, billingFilter,
                pageSize, (page - 1) * pageSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderRow order : rows) {
            UserRow account = order.getUserId() == null ? null : users.findUser(order.getUserId());
            PlanVersionRow version = order.getPlanVersionId() == null
                    ? null : billing.findVersionById(order.getPlanVersionId());
            PlanRow plan = version == null ? null : billing.findPlanById(version.getPlanId());
            items.add(orderPayload(order, account, version, plan));
        }

        Map<String, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : finance.statusSummary()) {
            Map<String, Long> bucket = new LinkedHashMap<>();
            bucket.put("orders", AFFlowOpsSupport.asLong(row.get("cnt")));
            bucket.put("amount_cents", AFFlowOpsSupport.asLong(row.get("cents")));
            grouped.put(String.valueOf(row.get("status")), bucket);
        }
        Map<String, Long> paid = grouped.getOrDefault("paid", zeroBucket());
        Map<String, Long> refunded = grouped.getOrDefault("refunded", zeroBucket());
        Map<String, Long> created = grouped.getOrDefault("created", zeroBucket());
        Map<String, Long> failed = grouped.getOrDefault("failed", zeroBucket());
        long totalOrders = 0;
        for (Map<String, Long> bucket : grouped.values()) {
            totalOrders += bucket.get("orders");
        }

        LocalDate today = Times.laToday();
        LocalDateTime dayStart = Times.dayStartUtc(today);
        Long todayPaid = finance.sumPaidSince(dayStart);
        Long todayOrders = finance.countCreatedSince(dayStart);

        Map<String, Object> summary = AFFlowOpsSupport.map(
                "total_orders", totalOrders,
                "paid_orders", paid.get("orders"),
                "refunded_orders", refunded.get("orders"),
                "created_orders", created.get("orders"),
                "failed_orders", failed.get("orders"),
                "paid_cents", paid.get("amount_cents"),
                "refunded_cents", refunded.get("amount_cents"),
                "pending_cents", created.get("amount_cents"),
                "gross_cents", paid.get("amount_cents") + refunded.get("amount_cents"),
                "net_cents", paid.get("amount_cents"),
                "today_paid_cents", todayPaid == null ? 0L : todayPaid,
                "today_orders", todayOrders == null ? 0L : todayOrders,
                "currency", "USD");
        return AFFlowOpsSupport.map(
                "items", items,
                "total", total,
                "page", page,
                "page_size", pageSize,
                "summary", summary);
    }

    private static Map<String, Long> zeroBucket() {
        Map<String, Long> bucket = new LinkedHashMap<>();
        bucket.put("orders", 0L);
        bucket.put("amount_cents", 0L);
        return bucket;
    }

    public Map<String, Object> financeOrderDetail(String orderNo) {
        OrderRow order = finance.findOrderByNo(orderNo);
        if (order == null) {
            throw ApiException.notFound("order not found");
        }
        UserRow account = order.getUserId() == null ? null : users.findUser(order.getUserId());
        PlanVersionRow version = order.getPlanVersionId() == null
                ? null : billing.findVersionById(order.getPlanVersionId());
        PlanRow plan = version == null ? null : billing.findPlanById(version.getPlanId());
        Map<String, Object> payload = orderPayload(order, account, version, plan);
        if (account == null) {
            payload.put("user_stats", null);
            payload.put("membership", null);
            return payload;
        }
        Long paidOrders = finance.countPaidByUser(account.getId());
        Long paidCents = finance.sumPaidByUser(account.getId());
        Long refundedCents = finance.sumRefundedByUser(account.getId());
        payload.put("user_stats", AFFlowOpsSupport.map(
                "paid_orders", paidOrders == null ? 0L : paidOrders,
                "paid_cents", paidCents == null ? 0L : paidCents,
                "refunded_cents", refundedCents == null ? 0L : refundedCents));
        payload.put("membership", billingService.activeMembership(account.getId()));
        return payload;
    }

    public Map<String, Object> financeLedger() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CoinLedgerRow row : finance.recentLedger()) {
            items.add(AFFlowOpsSupport.map(
                    "user_id", row.getUserId(),
                    "amount", row.getAmount(),
                    "biz_type", row.getBizType(),
                    "note", row.getNote()));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    /** One order row: legacy flat fields for the dashboard + nested user/plan. */
    private static Map<String, Object> orderPayload(OrderRow order, UserRow account,
                                                    PlanVersionRow version, PlanRow plan) {
        Map<String, Object> userPayload = null;
        if (account != null) {
            userPayload = AFFlowOpsSupport.map(
                    "id", account.getId(),
                    "public_id", account.getPublicId(),
                    "display_name", account.getDisplayName(),
                    "email", account.getEmail(),
                    "avatar_url", account.getAvatarUrl(),
                    "region", account.getRegion(),
                    "locale", account.getLocale(),
                    "created_at", account.getCreatedAt());
        }
        Map<String, Object> planPayload = null;
        if (plan != null) {
            planPayload = AFFlowOpsSupport.map(
                    "code", plan.getCode(),
                    "name_en", plan.getNameEn(),
                    "name_zh", plan.getNameZh(),
                    "version", version == null ? null : version.getVersion(),
                    "price_cents", version == null ? null : version.getPriceCents(),
                    "currency", version == null ? null : version.getCurrency(),
                    "benefits", version == null ? null : AFFlowOpsSupport.readMap(version.getBenefits()));
        }
        return AFFlowOpsSupport.map(
                "order_no", order.getOrderNo(),
                "user_id", order.getUserId(),
                "amount_cents", order.getAmountCents(),
                "currency", order.getCurrency(),
                "billing_mode", order.getBillingMode(),
                "status", order.getStatus(),
                "created_at", order.getCreatedAt(),
                "paid_at", order.getPaidAt(),
                "external_txn_id", order.getExternalTxnId(),
                "user", userPayload,
                "plan", planPayload);
    }
}
