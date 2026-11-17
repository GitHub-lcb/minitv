package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.ops.row.AdminApiKeyRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API-key lifecycle for the partner surface.
 *
 * <p>The base {@code AdminApiKeyMapper} only resolves credentials; lifecycle
 * writes live here so this module never touches another agent's files.
 */
@Mapper
public interface PartnerAdminApiKeyMapper {

    List<AdminApiKeyRow> listByAdminUser(@Param("adminUserId") long adminUserId);

    AdminApiKeyRow findById(@Param("id") long id);

    AdminApiKeyRow findByAdminUserAndId(@Param("adminUserId") long adminUserId, @Param("id") long id);

    AdminApiKeyRow findByPrefix(@Param("prefix") String prefix);

    int insertKey(AdminApiKeyRow row);

    int updateKeyFields(@Param("id") long id,
                        @Param("name") String name,
                        @Param("scopes") String scopes);

    int revokeKey(@Param("id") long id,
                  @Param("revokedAt") LocalDateTime revokedAt,
                  @Param("revokedBy") Long revokedBy,
                  @Param("revokeReason") String revokeReason);
}
