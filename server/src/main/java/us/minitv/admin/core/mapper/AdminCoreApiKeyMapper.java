package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.admin.core.model.AdminCoreApiKeyView;
import us.minitv.ops.row.AdminApiKeyRow;

import java.time.LocalDateTime;
import java.util.List;

/** admin_api_keys statements owned by the admin-core module. */
@Mapper
public interface AdminCoreApiKeyMapper {

    @Select("SELECT * FROM admin_api_keys WHERE id = #{id}")
    AdminApiKeyRow findById(@Param("id") long id);

    /** Global key list: keys joined with the bound account (api_key_router.list_keys). */
    List<AdminCoreApiKeyView> list(@Param("userId") Long userId,
                                   @Param("status") String status,
                                   @Param("q") String q,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    long count(@Param("userId") Long userId,
               @Param("status") String status,
               @Param("q") String q);

    /** Minimal columns for the four-way state summary (api_key_router.summary). */
    @Select("SELECT id, status, expires_at FROM admin_api_keys")
    List<AdminApiKeyRow> listAllStates();

    @Select("SELECT * FROM admin_api_keys WHERE admin_user_id = #{userId} ORDER BY id DESC")
    List<AdminApiKeyRow> listByUser(@Param("userId") long userId);

    @Insert("""
            INSERT INTO admin_api_keys (admin_user_id, name, key_prefix, key_hash, scopes, status,
                                        expires_at, created_by, created_at)
            VALUES (#{adminUserId}, #{name}, #{keyPrefix}, #{keyHash}, #{scopes}, #{status},
                    #{expiresAt}, #{createdBy}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminApiKeyRow row);

    int updateFields(@Param("id") long id,
                     @Param("setName") boolean setName,
                     @Param("name") String name,
                     @Param("setScopes") boolean setScopes,
                     @Param("scopes") String scopes,
                     @Param("setExpiresAt") boolean setExpiresAt,
                     @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE admin_api_keys
            SET status = 'revoked', revoked_at = #{revokedAt}, revoked_by = #{revokedBy}, revoke_reason = #{revokeReason}
            WHERE id = #{id}
            """)
    int revoke(@Param("id") long id,
               @Param("revokedAt") LocalDateTime revokedAt,
               @Param("revokedBy") Long revokedBy,
               @Param("revokeReason") String revokeReason);

    @Delete("DELETE FROM admin_api_keys WHERE id = #{id}")
    int deleteById(@Param("id") long id);
}
