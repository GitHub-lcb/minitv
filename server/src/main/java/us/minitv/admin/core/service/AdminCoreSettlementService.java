package us.minitv.admin.core.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreOrderMapper;
import us.minitv.admin.core.mapper.AdminCoreSettlementMapper;
import us.minitv.admin.core.mapper.AdminCoreSupplierMapper;
import us.minitv.admin.core.mapper.AdminCoreSupplierShareMapper;
import us.minitv.admin.core.model.AdminCoreSettlementView;
import us.minitv.billing.row.OrderRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;
import us.minitv.ops.row.SupplierSettlementRow;
import us.minitv.ops.row.SupplierShareHistoryRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monthly supplier settlements (ops/settlement_router.py + ops/settlements.py).
 *
 * Only the first-ever paid order of each user is attributed, using the revenue
 * share effective when that order was paid; confirmed/paid statements are
 * immutable.
 */
@Service
public class AdminCoreSettlementService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AdminCoreSettlementMapper settlements;
    private final AdminCoreSupplierMapper suppliers;
    private final AdminCoreOrderMapper orders;
    private final AdminCoreSupplierShareMapper shares;
    private final AdminCoreAuditService audit;

    public AdminCoreSettlementService(AdminCoreSettlementMapper settlements,
                                      AdminCoreSupplierMapper suppliers,
                                      AdminCoreOrderMapper orders,
                                      AdminCoreSupplierShareMapper shares,
                                      AdminCoreAuditService audit) {
        this.settlements = settlements;
        this.suppliers = suppliers;
        this.orders = orders;
        this.shares = shares;
        this.audit = audit;
    }

    public Map<String, Object> list(String month, String status, int page, int pageSize) {
        AdminCoreSupport.paging(page, pageSize);
        LocalDate monthStart = month == null ? null : AdminCoreSupport.monthBounds(month)[0];
        String statusFilter = status == null || status.isEmpty() ? null : status;
        long total = settlements.count(monthStart, statusFilter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminCoreSettlementView view : settlements.list(monthStart, statusFilter, pageSize, (page - 1) * pageSize)) {
            items.add(out(view));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        return out;
    }

    @Transactional
    public Map<String, Object> generate(AdminUserRow actor, String month) {
        LocalDate[] bounds = AdminCoreSupport.monthBounds(month);
        LocalDate start = bounds[0];
        LocalDate end = bounds[1];
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (SupplierRow supplier : suppliers.listAll()) {
            Computation data = compute(supplier, start, end);
            SupplierSettlementRow row = settlements.findBySupplierPeriod(supplier.getId(), start);
            // No orders and no statement yet -> nothing to say. An existing draft
            // still gets rewritten, otherwise a refund would leave stale numbers.
            if (data.orderCount == 0 && row == null) {
                continue;
            }
            if (row != null && !"draft".equals(row.getStatus())) {
                skipped++;
                continue;
            }
            if (row == null) {
                SupplierSettlementRow fresh = new SupplierSettlementRow();
                fresh.setSupplierId(supplier.getId());
                fresh.setPeriodStart(start);
                fresh.setPeriodEnd(end);
                fresh.setCurrency(data.currency);
                fresh.setGrossCents(data.grossCents);
                fresh.setRevenueCents(data.revenueCents);
                fresh.setEffectiveShareBps(data.effectiveShareBps);
                fresh.setOrderCount(data.orderCount);
                fresh.setStatus("draft");
                fresh.setNote("");
                settlements.insert(fresh);
                created++;
            } else {
                settlements.updateComputed(row.getId(), data.currency, data.grossCents, data.revenueCents,
                        data.effectiveShareBps, data.orderCount);
                updated++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        audit.record(actor.getId(), "settlements_generate", "settlement", 0L, result);
        return result;
    }

    @Transactional
    public Map<String, Object> changeStatus(AdminUserRow actor, long settlementId, String status, String note) {
        if (!"confirmed".equals(status) && !"paid".equals(status)) {
            throw ApiException.unprocessable("status: String should match pattern '^(confirmed|paid)$'");
        }
        String rawNote = note == null ? "" : note;
        if (rawNote.length() > 500) {
            throw ApiException.unprocessable("note: String should have at most 500 characters");
        }
        SupplierSettlementRow row = settlements.findById(settlementId);
        if (row == null) {
            throw ApiException.notFound("settlement not found");
        }
        LocalDateTime now = Times.now();
        String newStatus;
        LocalDateTime confirmedAt;
        LocalDateTime paidAt;
        if ("confirmed".equals(status)) {
            if (!"draft".equals(row.getStatus())) {
                throw ApiException.conflict("只有草稿可以确认");
            }
            newStatus = "confirmed";
            confirmedAt = now;
            paidAt = row.getPaidAt();
        } else {
            if (!"confirmed".equals(row.getStatus())) {
                throw ApiException.conflict("请先确认对账单");
            }
            newStatus = "paid";
            confirmedAt = row.getConfirmedAt();
            paidAt = now;
        }
        String cleanNote = rawNote.trim();
        boolean setNote = !cleanNote.isEmpty();
        settlements.updateStatus(row.getId(), newStatus, confirmedAt, paidAt, setNote, setNote ? cleanNote : null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("month", row.getPeriodStart().toString());
        payload.put("status", newStatus);
        audit.record(actor.getId(), "settlement_" + status, "settlement", row.getId(), payload);
        SupplierSettlementRow fresh = settlements.findById(row.getId());
        return out(fresh, suppliers.findById(fresh.getSupplierId()));
    }

    // ---- computation (settlements.compute / revenue_for_orders) ----

    private Computation compute(SupplierRow supplier, LocalDate start, LocalDate end) {
        List<OrderRow> firstOrders = orders.firstPaidOrdersForSupplier(supplier.getId(),
                start.atStartOfDay(), end.atStartOfDay());
        List<SupplierShareHistoryRow> history = shares.listAllAsc(supplier.getId());
        long gross = 0;
        long revenue = 0;
        long bpsWeight = 0;
        for (OrderRow order : firstOrders) {
            int bps = shareAt(history, order.getPaidAt(), supplier.getRevenueShareBps());
            long amount = order.getAmountCents() == null ? 0 : order.getAmountCents();
            gross += amount;
            revenue += amount * bps / 10000;
            bpsWeight += amount * bps;
        }
        // amount-weighted average, not revenue/gross: the latter loses 2bps to
        // integer rounding on a single order (1599/1999 -> 7998).
        int effectiveShareBps = gross != 0
                ? (int) (bpsWeight / gross)
                : (supplier.getRevenueShareBps() == null ? 0 : supplier.getRevenueShareBps());
        String currency = firstOrders.isEmpty()
                ? "USD"
                : (firstOrders.get(0).getCurrency() == null ? "USD" : firstOrders.get(0).getCurrency());
        return new Computation(firstOrders.size(), gross, revenue, effectiveShareBps, currency);
    }

    private static int shareAt(List<SupplierShareHistoryRow> history, LocalDateTime at, Integer fallback) {
        int chosen = fallback == null ? 0 : fallback;
        for (SupplierShareHistoryRow row : history) {
            LocalDateTime from = row.getEffectiveFrom();
            LocalDateTime to = row.getEffectiveTo();
            if (from == null) {
                continue;
            }
            if (!from.isAfter(at) && (to == null || at.isBefore(to))) {
                chosen = row.getShareBps();
            }
        }
        return chosen;
    }

    // ---- serialisers ----

    private static Map<String, Object> out(AdminCoreSettlementView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", view.getId());
        out.put("supplier", supplierRef(view.getSupplierId(), view.getSupplierName()));
        out.put("month", view.getPeriodStart() == null ? null : view.getPeriodStart().format(MONTH));
        out.put("period_start", view.getPeriodStart());
        out.put("period_end", view.getPeriodEnd());
        out.put("currency", view.getCurrency());
        out.put("gross_cents", view.getGrossCents());
        out.put("revenue_cents", view.getRevenueCents());
        out.put("effective_share_bps", view.getEffectiveShareBps());
        out.put("order_count", view.getOrderCount());
        out.put("status", view.getStatus());
        out.put("note", view.getNote());
        out.put("generated_at", view.getGeneratedAt());
        out.put("confirmed_at", view.getConfirmedAt());
        out.put("paid_at", view.getPaidAt());
        return out;
    }

    private static Map<String, Object> out(SupplierSettlementRow row, SupplierRow supplier) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("supplier", supplier == null ? null : supplierRef(supplier.getId(), supplier.getName()));
        out.put("month", row.getPeriodStart() == null ? null : row.getPeriodStart().format(MONTH));
        out.put("period_start", row.getPeriodStart());
        out.put("period_end", row.getPeriodEnd());
        out.put("currency", row.getCurrency());
        out.put("gross_cents", row.getGrossCents());
        out.put("revenue_cents", row.getRevenueCents());
        out.put("effective_share_bps", row.getEffectiveShareBps());
        out.put("order_count", row.getOrderCount());
        out.put("status", row.getStatus());
        out.put("note", row.getNote());
        out.put("generated_at", row.getGeneratedAt());
        out.put("confirmed_at", row.getConfirmedAt());
        out.put("paid_at", row.getPaidAt());
        return out;
    }

    private static Map<String, Object> supplierRef(Long supplierId, String supplierName) {
        if (supplierId == null || supplierName == null) {
            return null;
        }
        Map<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("id", supplierId);
        supplier.put("name", supplierName);
        return supplier;
    }

    private record Computation(int orderCount, long grossCents, long revenueCents,
                               int effectiveShareBps, String currency) {
    }
}
