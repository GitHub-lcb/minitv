package us.minitv.partner;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.common.row.AuditLogRow;
import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.partner.mapper.PartnerAuditLogMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit-trail writer, ported from {@code partner_router._audit} and
 * {@code api_keys._audit}. Rows always flow through the caller's transaction.
 */
@Service
public class PartnerAuditService {

    private final PartnerAuditLogMapper auditLogs;

    public PartnerAuditService(PartnerAuditLogMapper auditLogs) {
        this.auditLogs = auditLogs;
    }

    /**
     * {@code _audit(db, actor, action, drama_id, payload)}: target is the drama
     * when one is given, otherwise the actor's supplier; the payload always
     * carries {@code actor_role} and {@code supplier_id}.
     */
    public void partnerAction(AdminUserRow actor, String action, Long dramaId, Map<String, Object> payload) {
        AuditLogRow row = new AuditLogRow();
        row.setActorType("admin");
        row.setActorId(actor.getId());
        row.setAction(action);
        row.setTargetType(dramaId != null ? "drama" : "supplier");
        row.setTargetId(dramaId != null ? dramaId : actor.getSupplierId());
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("actor_role", "partner");
        body.put("supplier_id", actor.getSupplierId());
        row.setPayload(Json.write(body));
        auditLogs.insertAuditLog(row);
    }

    /** {@code api_keys._audit}: never stores the plaintext or the hash. */
    public void apiKeyAction(Long actorId, String action, AdminApiKeyRow key, Map<String, Object> extra) {
        AuditLogRow row = new AuditLogRow();
        row.setActorType("admin");
        row.setActorId(actorId);
        row.setAction(action);
        row.setTargetType("api_key");
        row.setTargetId(key.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", key.getName());
        payload.put("user_id", key.getAdminUserId());
        payload.put("scopes", scopesOf(key));
        payload.put("expires_at", key.getExpiresAt() == null ? null : Times.iso(key.getExpiresAt()));
        payload.putAll(extra);
        row.setPayload(Json.write(payload));
        auditLogs.insertAuditLog(row);
    }

    static List<String> scopesOf(AdminApiKeyRow key) {
        String raw = key.getScopes();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Json.read(raw, new TypeReference<List<String>>() {
        }, List.of());
    }
}
