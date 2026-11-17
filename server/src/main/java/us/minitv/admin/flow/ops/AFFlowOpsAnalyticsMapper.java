package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.analytics.row.DailyMetricRow;
import us.minitv.billing.row.OrderRow;
import us.minitv.playback.row.PlaybackEventRow;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.EntitlementRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Dashboard aggregates; every number comes from real product tables. */
@Mapper
public interface AFFlowOpsAnalyticsMapper {

    @Select("SELECT COUNT(*) FROM users")
    long countUsers();

    @Select("SELECT COUNT(*) FROM subscriptions WHERE status = 'active' AND current_period_end > NOW()")
    long countActiveMembers();

    @Select("SELECT COUNT(*) FROM dramas WHERE status = 'published'")
    long countPublishedDramas();

    @Select("SELECT COUNT(*) FROM episodes WHERE status = 'published'")
    long countPublishedEpisodes();

    @Select("SELECT COUNT(*) FROM episodes WHERE status IN ('transcoding', 'in_review')")
    long countAwaitingEpisodes();

    @Select("SELECT COUNT(*) FROM comments WHERE status = 'pending'")
    long countPendingComments();

    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM orders WHERE status = 'paid'")
    long sumLifetimeRevenueCents();

    @Select("SELECT COUNT(*) FROM playback_events WHERE kind = 'start'")
    long countLifetimePlays();

    @Select("SELECT created_at FROM users WHERE created_at >= #{start} AND created_at < #{end}")
    List<LocalDateTime> userCreatedAts(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT * FROM playback_events WHERE created_at >= #{start} AND created_at < #{end}")
    List<PlaybackEventRow> playbackEvents(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT * FROM orders WHERE status = 'paid' AND paid_at IS NOT NULL "
            + "AND paid_at >= #{start} AND paid_at < #{end}")
    List<OrderRow> paidOrders(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT * FROM coin_ledger WHERE amount < 0 AND created_at >= #{start} AND created_at < #{end}")
    List<CoinLedgerRow> negativeLedger(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT * FROM entitlements WHERE created_at >= #{start} AND created_at < #{end}")
    List<EntitlementRow> entitlements(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT d.id AS id, d.title AS title, "
            + "SUM(CASE WHEN e.kind = 'start' THEN 1 ELSE 0 END) AS plays, "
            + "SUM(CASE WHEN e.kind = 'complete' THEN 1 ELSE 0 END) AS completes "
            + "FROM playback_events e "
            + "JOIN episodes ep ON ep.id = e.episode_id "
            + "JOIN dramas d ON d.id = ep.drama_id "
            + "WHERE e.created_at >= #{start} AND e.created_at < #{end} "
            + "GROUP BY d.id, d.title ORDER BY plays DESC, d.id LIMIT #{limit}")
    List<Map<String, Object>> topDramas(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("limit") int limit);

    /** Batch upsert into daily_metrics, declared in afflowops_analytics.xml. */
    int upsertDailyMetrics(@Param("rows") List<DailyMetricRow> rows);
}
