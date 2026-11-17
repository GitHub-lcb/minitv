package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.billing.row.OrderRow;
import us.minitv.wallet.row.CoinLedgerRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Orders, their per-status totals and the coin ledger feed. */
@Mapper
public interface AFFlowOpsFinanceMapper {

    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} LIMIT 1")
    OrderRow findOrderByNo(@Param("orderNo") String orderNo);

    @Select("SELECT status, COUNT(*) AS cnt, COALESCE(SUM(amount_cents), 0) AS cents "
            + "FROM orders GROUP BY status")
    List<Map<String, Object>> statusSummary();

    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM orders "
            + "WHERE status = 'paid' AND paid_at >= #{since}")
    Long sumPaidSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM orders WHERE created_at >= #{since}")
    Long countCreatedSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND status = 'paid'")
    Long countPaidByUser(@Param("userId") long userId);

    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM orders WHERE user_id = #{userId} AND status = 'paid'")
    Long sumPaidByUser(@Param("userId") long userId);

    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM orders WHERE user_id = #{userId} AND status = 'refunded'")
    Long sumRefundedByUser(@Param("userId") long userId);

    @Select("SELECT * FROM coin_ledger ORDER BY id DESC LIMIT 100")
    List<CoinLedgerRow> recentLedger();

    /** Dynamic list/count statements live in afflowops_finance.xml. */
    List<OrderRow> searchOrders(@Param("needle") String needle, @Param("like") String like,
                                @Param("status") String status, @Param("billingMode") String billingMode,
                                @Param("limit") int limit, @Param("offset") int offset);

    long countOrders(@Param("needle") String needle, @Param("like") String like,
                     @Param("status") String status, @Param("billingMode") String billingMode);
}
