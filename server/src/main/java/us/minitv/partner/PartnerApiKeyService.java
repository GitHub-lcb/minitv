package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.partner.mapper.PartnerAdminApiKeyMapper;
import us.minitv.partner.mapper.PartnerAdminUserMapper;
import us.minitv.security.ApiKeyService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-service API keys for suppliers (P2), ported from the {@code "---- api keys ----"}
 * section of {@code partner_router} plus {@code ops/api_keys.py} lifecycle rules.
 *
 * <p>Credential management is session-only; a key may never mint another.
 */
@Service
public class PartnerApiKeyService {

    /** {@code api_keys.DEFAULT_TTL_DAYS} used by rotate. */
    private static final int DEFAULT_TTL_DAYS = 90;

    private final PartnerAdminApiKeyMapper keys;
    private final PartnerAdminUserMapper users;
    private final PartnerAuditService audit;

    public PartnerApiKeyService(PartnerAdminApiKeyMapper keys,
                                PartnerAdminUserMapper users,
                                PartnerAuditService audit) {
        this.keys = keys;
        this.users = users;
        this.audit = audit;
    }

    // ---- read ----

    public Map<String, Object> list(AdminUserRow user) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminApiKeyRow key : keys.listByAdminUser(user.getId())) {
            items.add(keyOut(key));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active", countState(items, "active"));
        summary.put("expiring", countState(items, "expiring"));
        summary.put("expired", countState(items, "expired"));
        summary.put("revoked", countState(items, "revoked"));
        summary.put("total", items.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("summary", summary);
        return payload;
    }

    public Map<String, Object> listOwn(AdminUserRow user) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminApiKeyRow key : keys.listByAdminUser(user.getId())) {
            items.add(keyOut(key));
        }
        return Map.of("items", items);
    }

    /**
     * Metadata of the calling key only — what the CLI needs for {@code whoami}.
     * Deliberately narrower than {@code /auth/keys}: a credential must not be able
     * to enumerate its owner's other keys.
     */
    public Map<String, Object> keyInfo(String credential) {
        if (!ApiKeyService.looksLikeKey(credential)) {
            throw PartnerErrors.badRequest("请使用 API Key 调用");
        }
        String prefix = credential.substring(0, Math.min(ApiKeyService.PREFIX_LEN, credential.length()));
        AdminApiKeyRow key = keys.findByPrefix(prefix);
        if (key == null) {
            throw PartnerErrors.notFound("key not found");
        }
        return keyOut(key);
    }

    // ---- lifecycle ----

    @Transactional
    public Map<String, Object> create(AdminUserRow actor, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        String rawName = PartnerBody.requiredString(body, "name", 1, 64);
        List<String> rawScopes = PartnerBody.optionalStringList(body, "scopes");
        Integer expiresInDays = expiresInDays(body);

        // Router-level whitelist first: suppliers only ever get read/write/upload.
        List<String> scopes = ApiKeyService.normaliseScopes(rawScopes, ApiKeyService.PARTNER_SCOPES);

        AdminUserRow user = users.findById(actor.getId());
        if (user == null) {
            throw PartnerErrors.notFound("staff not found");
        }
        if (!"active".equals(user.getStatus())) {
            throw PartnerErrors.unprocessable("账号已禁用，无法签发密钥");
        }
        List<String> finalScopes = ApiKeyService.normaliseScopes(scopes, null);
        LocalDateTime expiresAt = ApiKeyService.expiry(expiresInDays);

        ApiKeyService.KeyMaterial material = ApiKeyService.generateKey();
        AdminApiKeyRow row = new AdminApiKeyRow();
        row.setAdminUserId(user.getId());
        row.setName(PartnerText.strip(rawName));
        row.setKeyPrefix(material.prefix());
        row.setKeyHash(material.hash());
        row.setScopes(Json.write(finalScopes));
        row.setStatus("active");
        row.setExpiresAt(expiresAt);
        row.setCreatedBy(actor.getId());
        try {
            keys.insertKey(row);
        } catch (DuplicateKeyException ex) {
            throw keyNameConflict();
        }
        audit.apiKeyAction(actor.getId(), "api_key_create", row, Map.of());
        AdminApiKeyRow fresh = keys.findById(row.getId());
        Map<String, Object> payload = keyOut(fresh);
        payload.put("key", material.plaintext());
        return payload;
    }

    @Transactional
    public Map<String, Object> update(AdminUserRow actor, long keyId, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        AdminApiKeyRow key = ownKey(actor, keyId);
        if (!"active".equals(key.getStatus())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("key_revoked", "已撤销的密钥不能再修改"));
        }
        String name = PartnerBody.optionalString(body, "name", 1, 64);
        List<String> rawScopes = PartnerBody.optionalStringList(body, "scopes");
        List<String> scopes = null;
        if (rawScopes != null) {
            List<String> narrowed = ApiKeyService.normaliseScopes(rawScopes, ApiKeyService.PARTNER_SCOPES);
            scopes = ApiKeyService.normaliseScopes(narrowed, null);
        }

        String newName = null;
        if (name != null && !PartnerText.strip(name).equals(key.getName())) {
            newName = PartnerText.strip(name);
        }
        List<String> currentScopes = PartnerAuditService.scopesOf(key);
        String newScopesJson = null;
        if (scopes != null && !scopes.equals(currentScopes)) {
            newScopesJson = Json.write(scopes);
        }
        if (newName == null && newScopesJson == null) {
            return keyOut(key);
        }
        try {
            keys.updateKeyFields(keyId, newName, newScopesJson);
        } catch (DuplicateKeyException ex) {
            throw keyNameConflict();
        }
        if (newName != null) {
            key.setName(newName);
        }
        if (newScopesJson != null) {
            key.setScopes(newScopesJson);
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        if (newName != null) {
            changed.put("name", newName);
        }
        if (newScopesJson != null) {
            changed.put("scopes", scopes);
        }
        audit.apiKeyAction(actor.getId(), "api_key_update", key, Map.of("changed", changed));
        return keyOut(keys.findById(keyId));
    }

    /** Revoke the old key and mint a replacement in one transaction. */
    @Transactional
    public Map<String, Object> rotate(AdminUserRow actor, long keyId) {
        AdminApiKeyRow key = ownKey(actor, keyId);
        if (!"active".equals(key.getStatus())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("key_revoked", "已撤销的密钥不能轮换，请新建"));
        }
        LocalDateTime now = Times.now();
        keys.revokeKey(key.getId(), now, actor.getId(), "rotated");
        key.setStatus("revoked");
        key.setRevokedAt(now);
        key.setRevokedBy(actor.getId());
        key.setRevokeReason("rotated");

        List<String> scopes = PartnerAuditService.scopesOf(key);
        LocalDateTime expiresAt = ApiKeyService.expiry(DEFAULT_TTL_DAYS);
        ApiKeyService.KeyMaterial material = ApiKeyService.generateKey();
        AdminApiKeyRow freshRow = new AdminApiKeyRow();
        freshRow.setAdminUserId(key.getAdminUserId());
        freshRow.setName(key.getName());
        freshRow.setKeyPrefix(material.prefix());
        freshRow.setKeyHash(material.hash());
        freshRow.setScopes(Json.write(scopes));
        freshRow.setStatus("active");
        freshRow.setExpiresAt(expiresAt);
        freshRow.setCreatedBy(actor.getId());
        keys.insertKey(freshRow);
        audit.apiKeyAction(actor.getId(), "api_key_rotate", freshRow,
                Map.of("replaced", key.getId()));

        AdminApiKeyRow fresh = keys.findById(freshRow.getId());
        Map<String, Object> payload = keyOut(fresh);
        payload.put("key", material.plaintext());
        return payload;
    }

    @Transactional
    public Map<String, Object> revoke(AdminUserRow actor, long keyId, JsonNode bodyNode) {
        AdminApiKeyRow key = ownKey(actor, keyId);
        String reason = null;
        if (bodyNode != null && bodyNode.isObject()) {
            reason = PartnerBody.optionalString(bodyNode, "reason", 0, 200);
        }
        if ("revoked".equals(key.getStatus())) {
            return keyOut(key); // idempotent
        }
        String cleaned = reason == null ? null : PartnerText.strip(reason);
        if (cleaned != null && cleaned.isEmpty()) {
            cleaned = null;
        }
        LocalDateTime now = Times.now();
        keys.revokeKey(key.getId(), now, actor.getId(), cleaned);
        key.setStatus("revoked");
        key.setRevokedAt(now);
        key.setRevokedBy(actor.getId());
        key.setRevokeReason(cleaned);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", cleaned);
        audit.apiKeyAction(actor.getId(), "api_key_revoke", key, extra);
        return keyOut(keys.findById(keyId));
    }

    // ---- helpers ----

    private AdminApiKeyRow ownKey(AdminUserRow actor, long keyId) {
        AdminApiKeyRow key = keys.findByAdminUserAndId(actor.getId(), keyId);
        if (key == null) {
            throw PartnerErrors.notFound("key not found");
        }
        return key;
    }

    /** {@code expires_in_days: int | None = 90}: absent defaults to 90, explicit null means no expiry. */
    private static Integer expiresInDays(JsonNode body) {
        JsonNode node = body.get("expires_in_days");
        if (node == null) {
            return DEFAULT_TTL_DAYS;
        }
        if (node.isNull()) {
            return null;
        }
        return PartnerBody.requiredInt(body, "expires_in_days", null, null);
    }

    private static us.minitv.common.ApiException keyNameConflict() {
        return PartnerErrors.structured(409,
                PartnerErrors.detail("key_name_conflict", "该账号下已有同名密钥"));
    }

    private Map<String, Object> keyOut(AdminApiKeyRow key) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", key.getId());
        payload.put("name", key.getName());
        payload.put("key_prefix", key.getKeyPrefix());
        payload.put("scopes", PartnerAuditService.scopesOf(key));
        payload.put("state", keyState(key));
        payload.put("status", key.getStatus());
        payload.put("expires_at", key.getExpiresAt());
        payload.put("last_used_at", key.getLastUsedAt());
        payload.put("created_at", key.getCreatedAt());
        payload.put("revoked_at", key.getRevokedAt());
        payload.put("revoke_reason", key.getRevokeReason());
        return payload;
    }

    private static long countState(List<Map<String, Object>> items, String state) {
        long count = 0;
        for (Map<String, Object> item : items) {
            if (state.equals(item.get("state"))) {
                count++;
            }
        }
        return count;
    }

    /** Python {@code api_keys.key_state}: one of revoked / expired / expiring / active. */
    private static String keyState(AdminApiKeyRow key) {
        if (!"active".equals(key.getStatus())) {
            return "revoked";
        }
        if (key.getExpiresAt() == null) {
            return "active";
        }
        LocalDateTime now = Times.now();
        if (!key.getExpiresAt().isAfter(now)) {
            return "expired";
        }
        if (!key.getExpiresAt().isAfter(now.plusDays(14))) {
            return "expiring";
        }
        return "active";
    }
}
