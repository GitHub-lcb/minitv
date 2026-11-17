package us.minitv.security;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.ops.mapper.AdminApiKeyMapper;
import us.minitv.ops.mapper.AdminUserMapper;
import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API-key credentials: {@code mk_live_<8 hex>_<43 url-safe>}.
 *
 * A key's effective power is min(bound account's current role, scopes); the
 * role is re-read on every request and never cached in the key row. Redis is
 * only used for rate limiting and for throttling last_used_at writes - both
 * fail open so an outage cannot lock anyone out.
 */
@Service
public class ApiKeyService {

    public static final String KEY_PREFIX = "mk_live_";
    public static final int PREFIX_LEN = 16;
    public static final List<String> SCOPES = List.of("read", "write", "upload", "delete", "billing", "staff");
    public static final List<String> DEFAULT_SCOPES = List.of("read", "write");
    public static final List<String> PARTNER_SCOPES = List.of("read", "write", "upload");
    public static final List<Integer> ALLOWED_TTL_DAYS = List.of(30, 90, 180, 365);
    public static final Set<String> EVENT_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Pattern STAFF_PATH = Pattern.compile("^/admin/v1/(?:api-keys|staff)(?:/|$)");
    private static final Pattern PARTNER_KEY_PATH = Pattern.compile("^/partner/v1/(?:api-keys|auth/keys)(?:/|$)");
    private static final Pattern PARTNER_UPLOAD_PATH = Pattern.compile(
            "^/partner/v1/(?:episodes/\\d+/(?:upload-session|upload-headers)"
                    + "|dramas/\\d+/cover|dev-tus/[^/]+)$");
    private static final Pattern SELF_CREDENTIAL = Pattern.compile("^/admin/v1/auth/password$");
    private static final Pattern BILLING_PATH = Pattern.compile(
            "^/admin/v1/(?:finance|plans|rewards|coins|membership|billing)(?:/|$)");
    private static final Pattern DELETE_DRAMA = Pattern.compile("^/admin/v1/dramas/\\d+/delete$");
    private static final Pattern DELETE_EPISODE = Pattern.compile("^/admin/v1/episodes/\\d+$");

    private final AdminApiKeyMapper keys;
    private final AdminUserMapper users;
    private final RedisStore redis;
    private final int rateLimit;

    public ApiKeyService(AdminApiKeyMapper keys, AdminUserMapper users, RedisStore redis,
                         @org.springframework.beans.factory.annotation.Value("${API_KEY_RATE_LIMIT:600}") int rateLimit) {
        this.keys = keys;
        this.users = users;
        this.redis = redis;
        this.rateLimit = rateLimit;
    }

    public record KeyMaterial(String plaintext, String prefix, String hash) {
    }

    public record Resolved(AdminUserRow user, AdminApiKeyRow key) {
    }

    public static boolean looksLikeKey(String credential) {
        return credential != null && credential.startsWith(KEY_PREFIX);
    }

    public static String hashSecret(String plaintext) {
        return PasswordHasher.sha256Hex(plaintext);
    }

    public static KeyMaterial generateKey() {
        String prefix = KEY_PREFIX + Tokens.hex(4);
        String plaintext = prefix + "_" + Tokens.urlSafe(32);
        return new KeyMaterial(plaintext, prefix, hashSecret(plaintext));
    }

    /** Verify the credential and return its bound account; throws 401 shapes. */
    public Resolved resolveCredential(String credential) {
        String prefix = credential.substring(0, Math.min(PREFIX_LEN, credential.length()));
        AdminApiKeyRow key = keys.findByPrefix(prefix);
        if (key == null || !PasswordHasher.constantTimeEquals(key.getKeyHash(), hashSecret(credential))) {
            throw reject(401, "invalid_key", "API key 无效");
        }
        if (!"active".equals(key.getStatus())) {
            throw reject(401, "key_revoked", "API key 已被撤销");
        }
        LocalDateTime now = Times.now();
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now)) {
            throw reject(401, "key_expired", "API key 已过期，请轮换");
        }
        AdminUserRow user = users.findById(key.getAdminUserId());
        if (user == null || !"active".equals(user.getStatus())) {
            throw reject(401, "account_disabled", "密钥绑定的账号已被禁用");
        }
        enforceRateLimit(key.getId());
        touch(key);
        return new Resolved(user, key);
    }

    /** Scope policy: which permission a request needs (first match wins). */
    public static String scopeForRequest(String method, String path) {
        if (SELF_CREDENTIAL.matcher(path).find()) {
            return "staff";
        }
        if (STAFF_PATH.matcher(path).find()) {
            return "staff";
        }
        if (PARTNER_KEY_PATH.matcher(path).find()) {
            if ("/partner/v1/api-keys".equals(path) && "GET".equalsIgnoreCase(method)) {
                return "read";
            }
            return "staff";
        }
        if (PARTNER_UPLOAD_PATH.matcher(path).find()) {
            return "upload";
        }
        if (BILLING_PATH.matcher(path).find()) {
            return "billing";
        }
        if (DELETE_DRAMA.matcher(path).find()) {
            return "delete";
        }
        if ("DELETE".equalsIgnoreCase(method) && DELETE_EPISODE.matcher(path).find()) {
            return "delete";
        }
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method.toUpperCase())) {
            return "read";
        }
        return "write";
    }

    public static Set<String> grantedScopes(AdminApiKeyRow key, String role) {
        List<String> stored = key.getScopes() == null ? List.of()
                : Json.read(key.getScopes(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }, List.of());
        Set<String> scopes = stored.isEmpty() ? new LinkedHashSet<>(SCOPES) : new LinkedHashSet<>(stored);
        if ("partner".equals(role)) {
            scopes.retainAll(PARTNER_SCOPES);
        }
        return scopes;
    }

    public void authorize(AdminApiKeyRow key, String method, String path, String role) {
        if ("partner".equals(role) && !path.startsWith("/partner/v1/")) {
            throw reject(403, "partner_scope_denied", "上剧方密钥只能访问合作方接口");
        }
        String needed = scopeForRequest(method, path);
        Set<String> granted = grantedScopes(key, role);
        if ("upload".equals(needed) && granted.contains("write")) {
            return;
        }
        if (!granted.contains(needed)) {
            throw reject(403, "scope_denied", "该密钥缺少 " + needed + " 权限");
        }
    }

    public static List<String> normaliseScopes(List<String> raw, List<String> allowed) {
        if (raw == null) {
            return new ArrayList<>(DEFAULT_SCOPES);
        }
        if (raw.isEmpty()) {
            throw ApiException.unprocessable("至少选择一个权限；如需跟随账号角色请省略 scopes 字段");
        }
        List<String> pool = allowed == null ? SCOPES : allowed;
        List<String> unknown = raw.stream().filter(item -> !SCOPES.contains(item)).toList();
        List<String> forbidden = raw.stream().filter(item -> SCOPES.contains(item) && !pool.contains(item)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("unknown scope: " + String.join(", ", unknown)
                    + "（可选 " + String.join(", ", SCOPES) + "）");
        }
        if (!forbidden.isEmpty()) {
            throw ApiException.unprocessable("该角色不能授予 scope: " + String.join(", ", forbidden)
                    + "（可选 " + String.join(", ", pool) + "）");
        }
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    public static LocalDateTime expiry(Integer expiresInDays) {
        if (expiresInDays == null) {
            return null;
        }
        if (!ALLOWED_TTL_DAYS.contains(expiresInDays)) {
            List<String> days = ALLOWED_TTL_DAYS.stream().map(String::valueOf).toList();
            throw ApiException.unprocessable("有效期只能是 " + String.join("/", days) + " 天，或 null 表示长期");
        }
        return Times.now().plusDays(expiresInDays);
    }

    public static String keyState(AdminApiKeyRow key) {
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
        if (ChronoUnit.DAYS.between(now, key.getExpiresAt()) <= 14) {
            return "expiring";
        }
        return "active";
    }

    private void enforceRateLimit(long keyId) {
        if (rateLimit <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        String bucket = "minitv_key_rl:" + keyId + ":" + minute;
        try {
            long used = redis.increment(bucket);
            if (used == 1) {
                redis.expire(bucket, Duration.ofSeconds(70));
            }
            if (used > rateLimit) {
                throw new ApiException(429,
                        Map.of("code", "rate_limited", "message", "密钥超过 " + rateLimit + " 次/分钟限制"),
                        Map.of("Retry-After", "60"));
            }
        } catch (DataAccessException ex) {
            // fail open
        }
    }

    private void touch(AdminApiKeyRow key) {
        boolean fresh;
        try {
            fresh = Boolean.TRUE.equals(redis.setIfAbsent("minitv_key_touch:" + key.getId(), "1", Duration.ofSeconds(60)));
        } catch (DataAccessException ex) {
            fresh = true;
        }
        if (!fresh) {
            return;
        }
        keys.touchLastUsed(key.getId(), Times.now());
    }

    private static ApiException reject(int status, String code, String message) {
        return new ApiException(status, ApiException.detail(code, message),
                status == 401 ? Map.of("WWW-Authenticate", "Bearer") : Map.of());
    }
}
