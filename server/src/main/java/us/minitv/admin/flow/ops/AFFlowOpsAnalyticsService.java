package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.analytics.row.DailyMetricRow;
import us.minitv.billing.row.OrderRow;
import us.minitv.common.Times;
import us.minitv.playback.row.PlaybackEventRow;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.EntitlementRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dashboard aggregates, ported from analytics/service.py. Buckets are computed
 * in Java with Times.businessDay (America/Los_Angeles) because MySQL lacks
 * Postgres' {@code timezone()} helper; the SQL window filters stay identical.
 */
@Service
public class AFFlowOpsAnalyticsService {

    static final String TZ_NAME = "America/Los_Angeles";
    static final int TREND_WINDOW_MAX = 90;
    static final int TOP_DRAMAS_LIMIT = 5;
    static final List<String> METRIC_FIELDS = List.of(
            "active_users", "new_users", "plays", "completes", "paid_orders",
            "revenue_cents", "coin_spend", "unlock_count", "completion_rate");

    private final AFFlowOpsAnalyticsMapper analytics;

    public AFFlowOpsAnalyticsService(AFFlowOpsAnalyticsMapper analytics) {
        this.analytics = analytics;
    }

    private Map<LocalDate, Map<String, Object>> collect(LocalDate startDay, LocalDate endDay) {
        Map<LocalDate, Map<String, Object>> days = new LinkedHashMap<>();
        for (LocalDate cursor = startDay; !cursor.isAfter(endDay); cursor = cursor.plusDays(1)) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("day", cursor);
            for (String field : METRIC_FIELDS) {
                bucket.put(field, 0);
            }
            days.put(cursor, bucket);
        }
        LocalDateTime startUtc = Times.dayStartUtc(startDay);
        LocalDateTime endUtc = Times.dayStartUtc(endDay.plusDays(1));

        for (LocalDateTime createdAt : analytics.userCreatedAts(startUtc, endUtc)) {
            Map<String, Object> bucket = days.get(Times.businessDay(createdAt));
            if (bucket != null) {
                bucket.put("new_users", (Integer) bucket.get("new_users") + 1);
            }
        }

        Map<LocalDate, Set<Long>> activeUsers = new HashMap<>();
        for (PlaybackEventRow event : analytics.playbackEvents(startUtc, endUtc)) {
            LocalDate day = Times.businessDay(event.getCreatedAt());
            Map<String, Object> bucket = days.get(day);
            if (bucket == null) {
                continue;
            }
            if (event.getUserId() != null) {
                activeUsers.computeIfAbsent(day, key -> new HashSet<>()).add(event.getUserId());
            }
            if ("start".equals(event.getKind())) {
                bucket.put("plays", (Integer) bucket.get("plays") + 1);
            } else if ("complete".equals(event.getKind())) {
                bucket.put("completes", (Integer) bucket.get("completes") + 1);
            }
        }
        for (Map.Entry<LocalDate, Set<Long>> entry : activeUsers.entrySet()) {
            Map<String, Object> bucket = days.get(entry.getKey());
            if (bucket != null) {
                bucket.put("active_users", entry.getValue().size());
            }
        }

        for (OrderRow order : analytics.paidOrders(startUtc, endUtc)) {
            Map<String, Object> bucket = days.get(Times.businessDay(order.getPaidAt()));
            if (bucket == null) {
                continue;
            }
            bucket.put("paid_orders", (Integer) bucket.get("paid_orders") + 1);
            bucket.put("revenue_cents", (Integer) bucket.get("revenue_cents") + order.getAmountCents());
        }

        for (CoinLedgerRow entry : analytics.negativeLedger(startUtc, endUtc)) {
            Map<String, Object> bucket = days.get(Times.businessDay(entry.getCreatedAt()));
            if (bucket != null) {
                bucket.put("coin_spend", (Integer) bucket.get("coin_spend") - entry.getAmount());
            }
        }

        for (EntitlementRow entitlement : analytics.entitlements(startUtc, endUtc)) {
            Map<String, Object> bucket = days.get(Times.businessDay(entitlement.getCreatedAt()));
            if (bucket != null) {
                bucket.put("unlock_count", (Integer) bucket.get("unlock_count") + 1);
            }
        }

        for (Map<String, Object> bucket : days.values()) {
            int plays = (Integer) bucket.get("plays");
            int completes = (Integer) bucket.get("completes");
            bucket.put("completion_rate", plays == 0 ? 0 : completes * 10000 / plays);
        }
        return days;
    }

    private void snapshot(Map<LocalDate, Map<String, Object>> window) {
        List<DailyMetricRow> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Object>> entry : window.entrySet()) {
            Map<String, Object> bucket = entry.getValue();
            DailyMetricRow row = new DailyMetricRow();
            row.setBusinessDay(entry.getKey());
            row.setTimezone(TZ_NAME);
            row.setActiveUsers((Integer) bucket.get("active_users"));
            row.setNewUsers((Integer) bucket.get("new_users"));
            row.setPlays((Integer) bucket.get("plays"));
            row.setCompletes((Integer) bucket.get("completes"));
            row.setPaidOrders((Integer) bucket.get("paid_orders"));
            row.setRevenueCents((Integer) bucket.get("revenue_cents"));
            row.setCoinSpend((Integer) bucket.get("coin_spend"));
            row.setUnlockCount((Integer) bucket.get("unlock_count"));
            row.setCompletionRate((Integer) bucket.get("completion_rate"));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            analytics.upsertDailyMetrics(rows);
        }
    }

    @Transactional
    public Map<String, Object> dashboard(LocalDate day, int days) {
        LocalDate target = day == null ? Times.laToday() : day;
        int windowDays = Math.max(1, Math.min(days, TREND_WINDOW_MAX));
        LocalDate startDay = target.minusDays(windowDays - 1L);
        Map<LocalDate, Map<String, Object>> window = collect(startDay, target);
        snapshot(window);

        List<LocalDate> orderedDays = new ArrayList<>(window.keySet());
        Collections.sort(orderedDays);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate current : orderedDays) {
            trend.add(row(window.get(current)));
        }
        Map<String, Object> selected = trend.get(trend.size() - 1);
        Map<String, Object> previous = new LinkedHashMap<>();
        if (trend.size() > 1) {
            Map<String, Object> previousRow = trend.get(trend.size() - 2);
            for (String field : METRIC_FIELDS) {
                previous.put(field, previousRow.get(field));
            }
        } else {
            for (String field : METRIC_FIELDS) {
                previous.put(field, 0);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timezone", TZ_NAME);
        out.put("day", selected.get("day"));
        out.put("window", AFFlowOpsSupport.map(
                "start", startDay, "end", target, "days", windowDays));
        out.put("trend", trend);
        out.put("top_dramas", topDramas(startDay, target));
        out.put("overview", overview());
        for (String field : METRIC_FIELDS) {
            out.put(field, selected.get(field));
        }
        out.put("previous", previous);
        return out;
    }

    private static Map<String, Object> row(Map<String, Object> bucket) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", bucket.get("day"));
        for (String field : METRIC_FIELDS) {
            out.put(field, bucket.get(field));
        }
        return out;
    }

    private List<Map<String, Object>> topDramas(LocalDate startDay, LocalDate endDay) {
        LocalDateTime startUtc = Times.dayStartUtc(startDay);
        LocalDateTime endUtc = Times.dayStartUtc(endDay.plusDays(1));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : analytics.topDramas(startUtc, endUtc, TOP_DRAMAS_LIMIT)) {
            long plays = AFFlowOpsSupport.asLong(row.get("plays"));
            long completes = AFFlowOpsSupport.asLong(row.get("completes"));
            items.add(AFFlowOpsSupport.map(
                    "id", AFFlowOpsSupport.asLong(row.get("id")),
                    "title", row.get("title"),
                    "plays", plays,
                    "completes", completes,
                    "completion_rate", plays == 0 ? 0 : (int) (completes * 10000 / plays)));
        }
        return items;
    }

    private Map<String, Object> overview() {
        return AFFlowOpsSupport.map(
                "total_users", analytics.countUsers(),
                "active_members", analytics.countActiveMembers(),
                "published_dramas", analytics.countPublishedDramas(),
                "published_episodes", analytics.countPublishedEpisodes(),
                "episodes_awaiting_publish", analytics.countAwaitingEpisodes(),
                "pending_comments", analytics.countPendingComments(),
                "lifetime_revenue_cents", analytics.sumLifetimeRevenueCents(),
                "lifetime_plays", analytics.countLifetimePlays());
    }
}
