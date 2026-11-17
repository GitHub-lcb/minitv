package us.minitv.admin.core.service;

import org.springframework.stereotype.Service;
import us.minitv.admin.core.mapper.AdminCoreAuditLogMapper;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only audit trail writer for admin-core mutations (audit_logs). */
@Service
public class AdminCoreAuditService {

    private final AdminCoreAuditLogMapper auditLogs;

    public AdminCoreAuditService(AdminCoreAuditLogMapper auditLogs) {
        this.auditLogs = auditLogs;
    }

    public void record(Long actorId, String action, String targetType, Long targetId, Map<String, Object> payload) {
        AuditLogRow row = new AuditLogRow();
        row.setActorType("admin");
        row.setActorId(actorId);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setPayload(Json.write(payload == null ? new LinkedHashMap<>() : payload));
        auditLogs.insert(row);
    }
}
