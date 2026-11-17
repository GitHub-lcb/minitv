package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.SupplierRow;

import java.time.LocalDateTime;
import java.util.List;

/** suppliers statements owned by the admin-core module. */
@Mapper
public interface AdminCoreSupplierMapper {

    @Select("SELECT * FROM suppliers WHERE id = #{id}")
    SupplierRow findById(@Param("id") long id);

    /** Filters mirror ops.router.list_suppliers (q on name, status). */
    List<SupplierRow> list(@Param("needle") String needle, @Param("status") String status);

    /** settlements.generate walks every supplier in id order. */
    @Select("SELECT * FROM suppliers ORDER BY id")
    List<SupplierRow> listAll();

    @Select("SELECT COUNT(*) FROM suppliers WHERE name = #{name}")
    long countByName(@Param("name") String name);

    @Select("SELECT COUNT(*) FROM dramas WHERE supplier_id = #{supplierId}")
    long countDramas(@Param("supplierId") long supplierId);

    @Insert("""
            INSERT INTO suppliers (name, contact_name, contact_email, status, revenue_share_bps, note, created_at, updated_at)
            VALUES (#{name}, #{contactName}, #{contactEmail}, #{status}, #{revenueShareBps}, #{note}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SupplierRow row);

    int updateFields(@Param("id") long id,
                     @Param("setName") boolean setName,
                     @Param("name") String name,
                     @Param("setContactName") boolean setContactName,
                     @Param("contactName") String contactName,
                     @Param("setContactEmail") boolean setContactEmail,
                     @Param("contactEmail") String contactEmail,
                     @Param("setNote") boolean setNote,
                     @Param("note") String note,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE suppliers SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE suppliers SET revenue_share_bps = #{bps}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateShareBps(@Param("id") long id,
                       @Param("bps") int bps,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
