package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.SupplierShareHistoryRow;

import java.time.LocalDateTime;
import java.util.List;

/** supplier_share_history statements (effective-dated revenue share). */
@Mapper
public interface AdminCoreSupplierShareMapper {

    @Select("SELECT * FROM supplier_share_history WHERE supplier_id = #{supplierId} AND effective_to IS NULL")
    List<SupplierShareHistoryRow> listOpen(@Param("supplierId") long supplierId);

    @Update("UPDATE supplier_share_history SET effective_to = #{at} WHERE supplier_id = #{supplierId} AND effective_to IS NULL")
    int closeOpen(@Param("supplierId") long supplierId, @Param("at") LocalDateTime at);

    @Insert("""
            INSERT INTO supplier_share_history (supplier_id, share_bps, effective_from, effective_to, changed_by, note)
            VALUES (#{supplierId}, #{shareBps}, #{effectiveFrom}, #{effectiveTo}, #{changedBy}, #{note})
            """)
    int insert(SupplierShareHistoryRow row);

    @Select("""
            SELECT * FROM supplier_share_history
            WHERE supplier_id = #{supplierId}
            ORDER BY effective_from DESC, id DESC
            LIMIT #{limit}
            """)
    List<SupplierShareHistoryRow> listHistory(@Param("supplierId") long supplierId, @Param("limit") int limit);

    /** settlements.revenue_for_orders walks the effective-dated rows oldest first. */
    @Select("""
            SELECT * FROM supplier_share_history
            WHERE supplier_id = #{supplierId}
            ORDER BY effective_from, id
            """)
    List<SupplierShareHistoryRow> listAllAsc(@Param("supplierId") long supplierId);
}
