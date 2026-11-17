package us.minitv.partner;

import org.springframework.stereotype.Service;
import us.minitv.billing.row.OrderRow;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;
import us.minitv.ops.row.SupplierSettlementRow;
import us.minitv.ops.row.SupplierShareHistoryRow;
import us.minitv.partner.mapper.PartnerDramaMapper;
import us.minitv.partner.mapper.PartnerStatsMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supplier analytics and settlements (P4), ported from {@code partner_router}
 * and {@code ops/settlements.py}.
 *
 * <p>Only the <b>first paid order of each user ever</b> is attributed, and only
 * when that order carries a {@code source_drama_id} belonging to the supplier.
 */
@Service
public class PartnerStatsService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PartnerStatsMapper stats;
    private final PartnerDramaMapper dramas;

    public PartnerStatsService(PartnerStatsMapper stats, PartnerDramaMapper dramas) {
        this.stats = stats;
        this.dramas = dramas;
    }

    public Map<String, Object> overview(AdminUserRow user, SupplierRow supplier, int days) {
        requireDays(days);
        LocalDateTime now = Times.now();
        LocalDateTime start = now.minusDays(days);
        long supplierId = user.getSupplierId();

        long plays = 0;
        long completes = 0;
        for (Map<String, Object> row : stats.playCountsByKind(supplierId, start)) {
            String kind = str(row, "kind");
            long count = number(row, "cnt");
            if ("start".equals(kind)) {
                plays = count;
            } else if ("complete".equals(kind)) {
                completes = count;
            }
        }
        Map<String, Object> unlockRow = stats.unlockTotals(supplierId, start);
        long unlocks = number(unlockRow, "cnt");
        long unlockUsers = number(unlockRow, "userCount");
        long viewers = stats.viewerCount(supplierId, start);

        List<OrderRow> orders = stats.firstOrders(supplierId,
                start.toLocalDate().atStartOfDay(),
                now.toLocalDate().plusDays(1).atStartOfDay());
        Map<String, Object> money = revenueForOrders(supplier, orders);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("days", days);
        payload.put("plays", plays);
        payload.put("completes", completes);
        payload.put("completion_rate", plays > 0 ? round4((double) completes / plays) : 0);
        payload.put("viewers", viewers);
        payload.put("unlocks", unlocks);
        payload.put("unlock_users", unlockUsers);
        payload.put("first_orders", money.get("order_count"));
        payload.put("gross_cents", money.get("gross_cents"));
        payload.put("revenue_cents", money.get("revenue_cents"));
        payload.put("effective_share_bps", money.get("effective_share_bps"));
        payload.put("currency", money.get("currency"));
        return payload;
    }

    public Map<String, Object> dramas(AdminUserRow user, SupplierRow supplier, int days) {
        requireDays(days);
        LocalDateTime now = Times.now();
        LocalDateTime start = now.minusDays(days);
        long supplierId = user.getSupplierId();

        List<DramaRow> rows = dramas.listBySupplier(supplierId);
        List<Long> ids = new ArrayList<>();
        Set<Long> idSet = new HashSet<>();
        for (DramaRow row : rows) {
            ids.add(row.getId());
            idSet.add(row.getId());
        }

        Map<Long, Long> plays = new HashMap<>();
        Map<Long, Long> completes = new HashMap<>();
        Map<Long, Long> viewers = new HashMap<>();
        Map<Long, Long> unlocks = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : stats.dramaPlayCounts(ids, start)) {
                long dramaId = number(row, "dramaId");
                long count = number(row, "cnt");
                if ("start".equals(str(row, "kind"))) {
                    plays.put(dramaId, count);
                } else {
                    completes.put(dramaId, count);
                }
            }
            for (Map<String, Object> row : stats.dramaViewers(ids, start)) {
                viewers.put(number(row, "dramaId"), number(row, "cnt"));
            }
            for (Map<String, Object> row : stats.dramaUnlocks(ids, start)) {
                unlocks.put(number(row, "dramaId"), number(row, "cnt"));
            }
        }

        List<OrderRow> orders = stats.firstOrders(supplierId,
                start.toLocalDate().atStartOfDay(),
                now.toLocalDate().plusDays(1).atStartOfDay());
        List<SupplierShareHistoryRow> history = stats.shareHistory(supplierId);
        Map<Long, long[]> orderStats = new LinkedHashMap<>();
        for (OrderRow order : orders) {
            Long sourceDramaId = order.getSourceDramaId();
            if (sourceDramaId == null || !idSet.contains(sourceDramaId)) {
                continue;
            }
            int bps = shareAt(history, order.getPaidAt(), supplier.getRevenueShareBps());
            long[] item = orderStats.computeIfAbsent(sourceDramaId, key -> new long[3]);
            item[0] += 1;
            item[1] += order.getAmountCents();
            item[2] += (long) order.getAmountCents() * bps / 10000;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaRow drama : rows) {
            long dramaPlays = plays.getOrDefault(drama.getId(), 0L);
            long dramaCompletes = completes.getOrDefault(drama.getId(), 0L);
            long[] money = orderStats.getOrDefault(drama.getId(), new long[3]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", drama.getId());
            item.put("title", drama.getTitle());
            item.put("cover_url", drama.getCoverUrl());
            item.put("status", drama.getStatus());
            item.put("plays", dramaPlays);
            item.put("completion_rate",
                    dramaPlays > 0 ? round4((double) dramaCompletes / dramaPlays) : 0);
            item.put("viewers", viewers.getOrDefault(drama.getId(), 0L));
            item.put("unlocks", unlocks.getOrDefault(drama.getId(), 0L));
            item.put("first_orders", money[0]);
            item.put("gross_cents", money[1]);
            item.put("revenue_cents", money[2]);
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("days", days);
        payload.put("currency", "USD");
        payload.put("items", items);
        return payload;
    }

    public Map<String, Object> settlements(AdminUserRow user) {
        List<SupplierSettlementRow> rows = stats.settlements(user.getSupplierId(), 24);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SupplierSettlementRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("month", row.getPeriodStart() == null ? null : row.getPeriodStart().format(MONTH));
            item.put("currency", row.getCurrency());
            item.put("gross_cents", row.getGrossCents());
            item.put("revenue_cents", row.getRevenueCents());
            item.put("effective_share_bps", row.getEffectiveShareBps());
            item.put("order_count", row.getOrderCount());
            item.put("status", row.getStatus());
            item.put("confirmed_at", row.getConfirmedAt());
            item.put("paid_at", row.getPaidAt());
            items.add(item);
        }
        return Map.of("items", items);
    }

    // ---- settlement maths (ops/settlements.py) ----

    private Map<String, Object> revenueForOrders(SupplierRow supplier, List<OrderRow> orders) {
        List<SupplierShareHistoryRow> history = stats.shareHistory(supplier.getId());
        long gross = 0;
        long revenue = 0;
        long bpsWeight = 0;
        for (OrderRow order : orders) {
            int bps = shareAt(history, order.getPaidAt(), supplier.getRevenueShareBps());
            gross += order.getAmountCents();
            revenue += (long) order.getAmountCents() * bps / 10000;
            bpsWeight += (long) order.getAmountCents() * bps;
        }
        int fallbackBps = supplier.getRevenueShareBps() == null ? 0 : supplier.getRevenueShareBps();
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("order_count", (long) orders.size());
        money.put("gross_cents", gross);
        money.put("revenue_cents", revenue);
        // amount-weighted average, not revenue/gross: the latter loses 2bps to
        // integer rounding on a single order (1599/1999 -> 7998).
        money.put("effective_share_bps", gross != 0 ? bpsWeight / gross : fallbackBps);
        money.put("currency", orders.isEmpty() ? "USD" : orders.get(0).getCurrency());
        return money;
    }

    /** {@code _share_at}: the rate effective when the order was paid. */
    private static int shareAt(List<SupplierShareHistoryRow> history, LocalDateTime at, Integer fallback) {
        int chosen = fallback == null ? 0 : fallback;
        if (at == null) {
            return chosen;
        }
        for (SupplierShareHistoryRow row : history) {
            LocalDateTime start = row.getEffectiveFrom();
            LocalDateTime end = row.getEffectiveTo();
            if (start != null && !at.isBefore(start) && (end == null || at.isBefore(end))) {
                chosen = row.getShareBps() == null ? 0 : row.getShareBps();
            }
        }
        return chosen;
    }

    private static void requireDays(int days) {
        if (days < 1) {
            throw PartnerErrors.queryValidation("days",
                    "Input should be greater than or equal to 1", "greater_than_equal");
        }
        if (days > 90) {
            throw PartnerErrors.queryValidation("days",
                    "Input should be less than or equal to 90", "less_than_equal");
        }
    }

    /** Python {@code round(value, 4)} (banker's rounding on the shortest float repr). */
    private static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static long number(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        if (value == null) {
            // MySQL/JDBC may lower-case unquoted aliases; accept the snake_case spellings too.
            value = row.get(toSnake(key));
        }
        if (value == null) {
            value = 0L;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String str(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object value = row.get(key);
        if (value == null) {
            value = row.get(toSnake(key));
        }
        return value == null ? null : String.valueOf(value);
    }

    private static String toSnake(String key) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (Character.isUpperCase(character)) {
                out.append('_').append(Character.toLowerCase(character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
