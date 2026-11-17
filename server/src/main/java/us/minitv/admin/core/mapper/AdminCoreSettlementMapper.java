package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.admin.core.model.AdminCoreSettlementView;
import us.minitv.ops.row.SupplierSettlementRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** supplier_settlements statements (settlement_router.py / settlements.py). */
@Mapper
public interface AdminCoreSettlementMapper {

    @Select("SELECT * FROM supplier_settlements WHERE id = #{id}")
    SupplierSettlementRow findById(@Param("id") long id);

    @Select("""
            SELECT * FROM supplier_settlements
            WHERE supplier_id = #{supplierId} AND period_start = #{periodStart}
            ORDER BY id LIMIT 1
            """)
    SupplierSettlementRow findBySupplierPeriod(@Param("supplierId") long supplierId,
                                               @Param("periodStart") LocalDate periodStart);

    List<AdminCoreSettlementView> list(@Param("monthStart") LocalDate monthStart,
                                       @Param("status") String status,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    long count(@Param("monthStart") LocalDate monthStart, @Param("status") String status);

    @Insert("""
            INSERT INTO supplier_settlements (supplier_id, period_start, period_end, currency, gross_cents,
                                              revenue_cents, effective_share_bps, order_count, status, note, generated_at)
            VALUES (#{supplierId}, #{periodStart}, #{periodEnd}, #{currency}, #{grossCents},
                    #{revenueCents}, #{effectiveShareBps}, #{orderCount}, #{status}, #{note}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SupplierSettlementRow row);

    @Update("""
            UPDATE supplier_settlements
            SET currency = #{currency}, gross_cents = #{grossCents}, revenue_cents = #{revenueCents},
                effective_share_bps = #{effectiveShareBps}, order_count = #{orderCount}
            WHERE id = #{id}
            """)
    int updateComputed(@Param("id") long id,
                       @Param("currency") String currency,
                       @Param("grossCents") long grossCents,
                       @Param("revenueCents") long revenueCents,
                       @Param("effectiveShareBps") int effectiveShareBps,
                       @Param("orderCount") int orderCount);

    @Update("""
            <script>
            UPDATE supplier_settlements
            SET status = #{status},
                confirmed_at = #{confirmedAt},
                paid_at = #{paidAt}
                <if test="setNote">, note = #{note}</if>
            WHERE id = #{id}
            </script>
            """)
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("confirmedAt") LocalDateTime confirmedAt,
                     @Param("paidAt") LocalDateTime paidAt,
                     @Param("setNote") boolean setNote,
                     @Param("note") String note);
}
