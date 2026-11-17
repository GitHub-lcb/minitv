package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import us.minitv.common.row.AuditLogRow;

/** Audit trail appends (table {@code audit_logs}). */
@Mapper
public interface AFContentAuditMapper {

    @Insert("INSERT INTO audit_logs (actor_type, actor_id, action, target_type, target_id, payload) "
            + "VALUES (#{actorType}, #{actorId}, #{action}, #{targetType}, #{targetId}, CAST(#{payload} AS JSON))")
    int insert(AuditLogRow row);
}
