package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import us.minitv.common.row.AuditLogRow;

/** audit_logs is append-only; admin-core writes its own audit rows here. */
@Mapper
public interface AdminCoreAuditLogMapper {

    @Insert("""
            INSERT INTO audit_logs (actor_type, actor_id, action, target_type, target_id, payload, created_at)
            VALUES (#{actorType}, #{actorId}, #{action}, #{targetType}, #{targetId}, #{payload}, NOW())
            """)
    int insert(AuditLogRow row);
}
