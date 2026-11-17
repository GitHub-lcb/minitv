package us.minitv.admin.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreAdminUserMapper;
import us.minitv.admin.core.mapper.AdminCoreApiKeyEventMapper;
import us.minitv.admin.core.mapper.AdminCoreApiKeyMapper;
import us.minitv.admin.core.model.AdminCoreApiKeyView;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.ApiKeyEventRow;
import us.minitv.security.ApiKeyService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API-key lifecycle (api_key_router.py + ops/api_keys.py).
 *
 * A key's effective power is min(bound account role, scopes); the plaintext
 * exists only in the create/rotate response and only its sha256 is stored.
 */
@Service
public class AdminCoreApiKeyService {

    public static final int DEFAULT_TTL_DAYS = 90;

    private final AdminCoreApiKeyMapper keys;
    private final AdminCoreAdminUserMapper users;
    private final AdminCoreApiKeyEventMapper events;
    private final AdminCoreAuditService audit;

    public AdminCoreApiKeyService(AdminCoreApiKeyMapper keys,
                                  AdminCoreAdminUserMapper users,
                                  AdminCoreApiKeyEventMapper events,
                                  AdminCoreAuditService audit) {
        this.keys = keys;
        this.users = users;
        this.events = events;
        this.audit = audit;
    }

    // ---- read ----

    public Map<String, Object> listKeys(Long userId, String status, String q, int page, int pageSize) {
        AdminCoreSupport.paging(page, pageSize);
        String statusFilter = ("active".equals(status) || "revoked".equals(status)) ? status : null;
        String needle = AdminCoreSupport.needle(q);
        String qFilter = needle.isEmpty() ? null : needle;
        long total = keys.count(userId, statusFilter, qFilter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminCoreApiKeyView view : keys.list(userId, statusFilter, qFilter, pageSize, (page - 1) * pageSize)) {
            items.add(keyOut(view));
        }
        // Global counts, unaffected by filters (same idea as the drama list header).
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active", 0);
        summary.put("expiring", 0);
        summary.put("expired", 0);
        summary.put("revoked", 0);
        for (AdminApiKeyRow row : keys.listAllStates()) {
            String state = ApiKeyService.keyState(row);
            summary.put(state, ((Number) summary.getOrDefault(state, 0)).intValue() + 1);
        }
        long summaryTotal = 0;
        for (String state : List.of("active", "expiring", "expired", "revoked")) {
            summaryTotal += ((Number) summary.get(state)).longValue();
        }
        summary.put("total", summaryTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("summary", summary);
        return out;
    }

    public Map<String, Object> listKeyEvents(long keyId, int limit) {
        AdminApiKeyRow key = keys.findById(keyId);
        if (key == null) {
            throw new ApiException(404, ApiException.detail("key_not_found", "密钥不存在"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApiKeyEventRow row : events.listByKey(keyId, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("method", row.getMethod());
            item.put("path", row.getPath());
            item.put("status_code", row.getStatusCode());
            item.put("duration_ms", row.getDurationMs());
            item.put("created_at", row.getCreatedAt());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    public Map<String, Object> listOwnKeys(AdminUserRow actor) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminApiKeyRow row : keys.listByUser(actor.getId())) {
            items.add(keyOut(row, actor));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    // ---- write ----

    @Transactional
    public Map<String, Object> createKey(AdminUserRow actor, long userId, String name,
                                         List<String> scopes, Integer expiresInDays) {
        AdminUserRow user = users.findById(userId);
        if (user == null) {
            throw ApiException.notFound("staff not found");
        }
        if (!"active".equals(user.getStatus())) {
            throw ApiException.unprocessable("账号已禁用，无法签发密钥");
        }
        List<String> cleanScopes = ApiKeyService.normaliseScopes(scopes, null);
        LocalDateTime expiresAt = ApiKeyService.expiry(expiresInDays);
        ApiKeyService.KeyMaterial material = ApiKeyService.generateKey();
        AdminApiKeyRow row = new AdminApiKeyRow();
        row.setAdminUserId(user.getId());
        row.setName(name.trim());
        row.setKeyPrefix(material.prefix());
        row.setKeyHash(material.hash());
        row.setScopes(Json.write(cleanScopes));
        row.setStatus("active");
        row.setExpiresAt(expiresAt);
        row.setCreatedBy(actor.getId());
        try {
            keys.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, ApiException.detail("key_name_conflict", "该账号下已有同名密钥"));
        }
        AdminApiKeyRow fresh = keys.findById(row.getId());
        auditKey(actor.getId(), "api_key_create", fresh, new LinkedHashMap<>());
        return issueOut(fresh, user, material.plaintext());
    }

    @Transactional
    public Map<String, Object> updateKey(AdminUserRow actor, long keyId, Map<String, Object> body) {
        AdminApiKeyRow key = keys.findById(keyId);
        if (key == null) {
            throw new ApiException(404, ApiException.detail("key_not_found", "密钥不存在"));
        }
        if (!"active".equals(key.getStatus())) {
            throw new ApiException(409, ApiException.detail("key_revoked", "已撤销的密钥不能再修改"));
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        boolean setName = false;
        String newName = null;
        boolean setScopes = false;
        String newScopesJson = null;
        boolean setExpiresAt = false;
        LocalDateTime newExpiresAt = null;

        if (AdminCoreSupport.hasValue(body, "name")) {
            String raw = AdminCoreSupport.bodyString(body, "name", 1, 64);
            String clean = raw.trim();
            if (!clean.equals(key.getName())) {
                newName = clean;
                setName = true;
                changed.put("name", clean);
            }
        }
        if (AdminCoreSupport.hasValue(body, "scopes")) {
            List<String> rawScopes = AdminCoreSupport.bodyStringList(body, "scopes");
            List<String> cleanScopes = ApiKeyService.normaliseScopes(rawScopes, null);
            if (!cleanScopes.equals(scopesOf(key.getScopes()))) {
                newScopesJson = Json.write(cleanScopes);
                setScopes = true;
                changed.put("scopes", cleanScopes);
            }
        }
        if (body != null && body.containsKey("expires_at")) {
            LocalDateTime value = AdminCoreSupport.bodyDateTime(body, "expires_at");
            newExpiresAt = value;
            setExpiresAt = true;
            changed.put("expires_at", AdminCoreSupport.isoOrNull(value));
        }
        if (changed.isEmpty()) {
            return keyOut(key, users.findById(key.getAdminUserId()));
        }
        try {
            keys.updateFields(key.getId(), setName, newName, setScopes, newScopesJson, setExpiresAt, newExpiresAt);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, ApiException.detail("key_name_conflict", "该账号下已有同名密钥"));
        }
        AdminApiKeyRow fresh = keys.findById(key.getId());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("changed", changed);
        auditKey(actor.getId(), "api_key_update", fresh, extra);
        return keyOut(fresh, users.findById(fresh.getAdminUserId()));
    }

    @Transactional
    public Map<String, Object> revokeKey(AdminUserRow actor, long keyId, String reason) {
        AdminApiKeyRow key = keys.findById(keyId);
        if (key == null) {
            throw new ApiException(404, ApiException.detail("key_not_found", "密钥不存在"));
        }
        if ("revoked".equals(key.getStatus())) {
            return keyOut(key, users.findById(key.getAdminUserId())); // idempotent
        }
        keys.revoke(key.getId(), Times.now(), actor.getId(), AdminCoreSupport.stripToNull(reason));
        AdminApiKeyRow fresh = keys.findById(key.getId());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", fresh.getRevokeReason());
        auditKey(actor.getId(), "api_key_revoke", fresh, extra);
        return keyOut(fresh, users.findById(fresh.getAdminUserId()));
    }

    @Transactional
    public Map<String, Object> rotateKey(AdminUserRow actor, long keyId, Integer expiresInDays) {
        AdminApiKeyRow key = keys.findById(keyId);
        if (key == null) {
            throw new ApiException(404, ApiException.detail("key_not_found", "密钥不存在"));
        }
        if (!"active".equals(key.getStatus())) {
            throw new ApiException(409, ApiException.detail("key_revoked", "已撤销的密钥不能轮换，请新建"));
        }
        List<String> storedScopes = scopesOf(key.getScopes());
        LocalDateTime expiresAt = ApiKeyService.expiry(expiresInDays);
        keys.revoke(key.getId(), Times.now(), actor.getId(), "rotated");
        ApiKeyService.KeyMaterial material = ApiKeyService.generateKey();
        AdminApiKeyRow replacement = new AdminApiKeyRow();
        replacement.setAdminUserId(key.getAdminUserId());
        replacement.setName(key.getName());
        replacement.setKeyPrefix(material.prefix());
        replacement.setKeyHash(material.hash());
        replacement.setScopes(Json.write(storedScopes));
        replacement.setStatus("active");
        replacement.setExpiresAt(expiresAt);
        replacement.setCreatedBy(actor.getId());
        try {
            keys.insert(replacement);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, ApiException.detail("key_name_conflict", "该账号下已有同名密钥"));
        }
        AdminApiKeyRow fresh = keys.findById(replacement.getId());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("replaced", key.getId());
        auditKey(actor.getId(), "api_key_rotate", fresh, extra);
        return issueOut(fresh, users.findById(fresh.getAdminUserId()), material.plaintext());
    }

    @Transactional
    public void deleteKey(AdminUserRow actor, long keyId) {
        AdminApiKeyRow key = keys.findById(keyId);
        if (key == null) {
            throw new ApiException(404, ApiException.detail("key_not_found", "密钥不存在"));
        }
        if (key.getLastUsedAt() != null) {
            throw new ApiException(409, ApiException.detail("key_in_use", "密钥已被使用，是审计证据，请改为撤销"));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", key.getName());
        snapshot.put("user_id", key.getAdminUserId());
        snapshot.put("scopes", scopesOf(key.getScopes()));
        keys.deleteById(key.getId());
        audit.record(actor.getId(), "api_key_delete", "api_key", null, snapshot);
    }

    // ---- serialisers / helpers ----

    private Map<String, Object> keyOut(AdminApiKeyRow key, AdminUserRow user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", key.getId());
        out.put("name", key.getName());
        out.put("user_id", key.getAdminUserId());
        out.put("username", user == null ? null : user.getUsername());
        out.put("display_name", user == null ? "" : user.getDisplayName());
        out.put("key_prefix", key.getKeyPrefix());
        out.put("scopes", scopesOf(key.getScopes()));
        out.put("state", stateOf(key.getStatus(), key.getExpiresAt()));
        out.put("status", key.getStatus());
        out.put("expires_at", key.getExpiresAt());
        out.put("last_used_at", key.getLastUsedAt());
        out.put("created_at", key.getCreatedAt());
        out.put("revoked_at", key.getRevokedAt());
        out.put("revoke_reason", key.getRevokeReason());
        return out;
    }

    private Map<String, Object> keyOut(AdminCoreApiKeyView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", view.getId());
        out.put("name", view.getName());
        out.put("user_id", view.getAdminUserId());
        out.put("username", view.getUsername());
        out.put("display_name", view.getDisplayName() == null ? "" : view.getDisplayName());
        out.put("key_prefix", view.getKeyPrefix());
        out.put("scopes", scopesOf(view.getScopes()));
        out.put("state", stateOf(view.getStatus(), view.getExpiresAt()));
        out.put("status", view.getStatus());
        out.put("expires_at", view.getExpiresAt());
        out.put("last_used_at", view.getLastUsedAt());
        out.put("created_at", view.getCreatedAt());
        out.put("revoked_at", view.getRevokedAt());
        out.put("revoke_reason", view.getRevokeReason());
        return out;
    }

    /** Create / rotate response - the only place the plaintext ever appears. */
    private Map<String, Object> issueOut(AdminApiKeyRow key, AdminUserRow user, String plaintext) {
        Map<String, Object> out = keyOut(key, user);
        out.put("key", plaintext);
        return out;
    }

    private static String stateOf(String status, LocalDateTime expiresAt) {
        AdminApiKeyRow shim = new AdminApiKeyRow();
        shim.setStatus(status);
        shim.setExpiresAt(expiresAt);
        return ApiKeyService.keyState(shim);
    }

    private static List<String> scopesOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return Json.read(raw, new TypeReference<List<String>>() {
        }, new ArrayList<>());
    }

    private void auditKey(Long actorId, String action, AdminApiKeyRow key, Map<String, Object> extra) {
        // Never store the plaintext or the hash here.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", key.getName());
        payload.put("user_id", key.getAdminUserId());
        payload.put("scopes", scopesOf(key.getScopes()));
        payload.put("expires_at", AdminCoreSupport.isoOrNull(key.getExpiresAt()));
        payload.putAll(extra);
        audit.record(actorId, action, "api_key", key.getId(), payload);
    }
}
