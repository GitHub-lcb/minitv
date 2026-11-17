package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.AdminUserRow;

import java.time.LocalDateTime;
import java.util.List;

/** admin_users statements owned by the admin-core module. */
@Mapper
public interface AdminCoreAdminUserMapper {

    @Select("SELECT * FROM admin_users WHERE id = #{id}")
    AdminUserRow findById(@Param("id") long id);

    @Select("SELECT * FROM admin_users WHERE username = #{username}")
    AdminUserRow findByUsername(@Param("username") String username);

    @Select("SELECT * FROM admin_users WHERE supplier_id = #{supplierId} ORDER BY id LIMIT 1")
    AdminUserRow findBySupplierId(@Param("supplierId") long supplierId);

    @Select("SELECT COUNT(*) FROM admin_users WHERE username = #{username}")
    long countByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM admin_users WHERE `role` = 'admin' AND status = 'active' AND id <> #{excludeId}")
    long countOtherActiveAdmins(@Param("excludeId") long excludeId);

    /** Filters mirror ops.router.list_staff (q on username/display_name, role, status). */
    List<AdminUserRow> list(@Param("needle") String needle,
                            @Param("role") String role,
                            @Param("status") String status);

    @Insert("""
            INSERT INTO admin_users (username, password_hash, display_name, `role`, status, supplier_id, created_at, updated_at)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, #{status}, #{supplierId}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminUserRow row);

    int updateProfile(@Param("id") long id,
                      @Param("setDisplayName") boolean setDisplayName,
                      @Param("displayName") String displayName,
                      @Param("setRole") boolean setRole,
                      @Param("role") String role,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE admin_users SET password_hash = #{passwordHash}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updatePassword(@Param("id") long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE admin_users SET status = #{status}, disable_reason = #{disableReason}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("disableReason") String disableReason,
                     @Param("updatedAt") LocalDateTime updatedAt);

    /** SQLAlchemy bumped updated_at through Column(onupdate=now()) on this path too. */
    @Update("UPDATE admin_users SET last_login_at = #{at}, updated_at = #{at} WHERE id = #{id}")
    int touchLastLogin(@Param("id") long id, @Param("at") LocalDateTime at);
}
