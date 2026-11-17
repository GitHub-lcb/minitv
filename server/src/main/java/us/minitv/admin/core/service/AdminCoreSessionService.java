package us.minitv.admin.core.service;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreAdminUserMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.security.PasswordHasher;
import us.minitv.security.RedisStore;
import us.minitv.security.SessionService;
import us.minitv.security.Tokens;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Staff login/logout and password rotation, backed by the same Redis session
 * store as the Python service ({@code minitv_admin_session:}, 8h TTL).
 */
@Service
public class AdminCoreSessionService {

    private final AdminCoreAdminUserMapper users;
    private final RedisStore redis;
    private final AdminCoreAuditService audit;

    public AdminCoreSessionService(AdminCoreAdminUserMapper users, RedisStore redis, AdminCoreAuditService audit) {
        this.users = users;
        this.redis = redis;
        this.audit = audit;
    }

    /** ops.service.login: returns {"token", "user": AdminUserOut}. */
    @Transactional
    public Map<String, Object> login(String username, String password) {
        AdminUserRow user = users.findByUsername(username);
        if (user == null || !"active".equals(user.getStatus())) {
            throw ApiException.of(401, "invalid credentials");
        }
        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            throw ApiException.of(401, "invalid credentials");
        }
        String token = Tokens.urlSafe(32);
        try {
            redis.set(SessionService.ADMIN_PREFIX + token, String.valueOf(user.getId()), SessionService.ADMIN_TTL);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable(AdminCoreSupport.SESSION_STORE_503_DETAIL);
        }
        // Only a login that actually got a session counts as "last seen".
        users.touchLastLogin(user.getId(), Times.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("user", adminUserOut(user));
        return out;
    }

    /** ops_service.logout: drops one session token (a no-op for API-key credentials). */
    public void logout(String token) {
        try {
            redis.delete(SessionService.ADMIN_PREFIX + token);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable(AdminCoreSupport.SESSION_STORE_503_DETAIL);
        }
    }

    /**
     * Self-service password change. Every session of the account is revoked
     * before the new hash is written, so a Redis outage fails the request
     * instead of silently leaving the old credential alive.
     */
    @Transactional
    public Map<String, Object> changePassword(AdminUserRow user, String oldPassword, String newPassword) {
        AdminCoreSupport.stringLength("old_password", oldPassword, 1, 128);
        AdminCoreSupport.stringLength("new_password", newPassword, 8, 128);
        if (!PasswordHasher.verify(oldPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("原密码不正确");
        }
        if (newPassword.equals(oldPassword)) {
            throw ApiException.unprocessable("新密码不能与原密码相同");
        }
        AdminCoreSupport.validatePassword(newPassword, user.getUsername());
        int killed = revokeSessions(user.getId());
        users.updatePassword(user.getId(), PasswordHasher.hash(newPassword), Times.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revoked_sessions", killed);
        audit.record(user.getId(), "staff_password_change", "admin_user", user.getId(), payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revoked_sessions", killed);
        return out;
    }

    /** ops_service.revoke_user_sessions: scans the session prefix and drops this account's tokens. */
    public int revokeSessions(long userId) {
        try {
            int killed = 0;
            Set<String> keys = redis.scan(SessionService.ADMIN_PREFIX + "*");
            for (String key : keys) {
                if (String.valueOf(userId).equals(redis.get(key))) {
                    redis.delete(key);
                    killed++;
                }
            }
            return killed;
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable(AdminCoreSupport.SESSION_STORE_503_DETAIL);
        }
    }

    /** AdminUserOut.model_dump(): id, username, display_name, role only. */
    public static Map<String, Object> adminUserOut(AdminUserRow user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("username", user.getUsername());
        out.put("display_name", user.getDisplayName());
        out.put("role", user.getRole());
        return out;
    }
}
