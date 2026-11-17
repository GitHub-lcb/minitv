package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import us.minitv.common.row.AuditLogRow;

/** Audit trail write used by /v1/config's copy fallback. */
@Mapper
public interface PCatalogAuditMapper {

    @Insert("INSERT INTO audit_logs (actor_type, actor_id, action, target_type, target_id, payload) "
            + "VALUES (#{actorType}, #{actorId}, #{action}, #{targetType}, #{targetId}, #{payload})")
    int insert(AuditLogRow row);
}
