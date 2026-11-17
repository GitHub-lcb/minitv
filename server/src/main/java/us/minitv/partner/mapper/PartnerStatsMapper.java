package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.billing.row.OrderRow;
import us.minitv.ops.row.SupplierShareHistoryRow;
import us.minitv.ops.row.SupplierSettlementRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Cross-table analytics queries for /partner/v1/stats/* and /partner/v1/settlements. */
@Mapper
public interface PartnerStatsMapper {

    List<Map<String, Object>> playCountsByKind(@Param("supplierId") long supplierId,
                                               @Param("start") LocalDateTime start);

    Map<String, Object> unlockTotals(@Param("supplierId") long supplierId,
                                     @Param("start") LocalDateTime start);

    long viewerCount(@Param("supplierId") long supplierId, @Param("start") LocalDateTime start);

    List<OrderRow> firstOrders(@Param("supplierId") long supplierId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    List<SupplierShareHistoryRow> shareHistory(@Param("supplierId") long supplierId);

    List<SupplierSettlementRow> settlements(@Param("supplierId") long supplierId,
                                            @Param("limit") int limit);

    List<Map<String, Object>> dramaPlayCounts(@Param("dramaIds") List<Long> dramaIds,
                                              @Param("start") LocalDateTime start);

    List<Map<String, Object>> dramaViewers(@Param("dramaIds") List<Long> dramaIds,
                                           @Param("start") LocalDateTime start);

    List<Map<String, Object>> dramaUnlocks(@Param("dramaIds") List<Long> dramaIds,
                                           @Param("start") LocalDateTime start);
}
