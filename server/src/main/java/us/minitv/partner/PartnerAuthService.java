package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;
import us.minitv.partner.mapper.PartnerAdminUserMapper;
import us.minitv.security.PartnerContext;
import us.minitv.security.PasswordHasher;
import us.minitv.security.RedisStore;
import us.minitv.security.SessionService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Supplier profile and credential routes: {@code GET /me},
 * {@code POST /auth/password}, {@code POST /auth/logout} (P1).
 */
@Service
public class PartnerAuthService {

    private final PartnerAdminUserMapper adminUsers;
    private final RedisStore redis;

    public PartnerAuthService(PartnerAdminUserMapper adminUsers, RedisStore redis) {
        this.adminUsers = adminUsers;
        this.redis = redis;
    }

    public Map<String, Object> me(PartnerContext partner) {
        AdminUserRow user = partner.user();
        SupplierRow supplier = partner.supplier();
        Map<String, Object> supplierPayload = new LinkedHashMap<>();
        supplierPayload.put("id", supplier.getId());
        supplierPayload.put("name", supplier.getName());
        supplierPayload.put("status", supplier.getStatus());
        supplierPayload.put("revenue_share_bps", supplier.getRevenueShareBps());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("display_name", user.getDisplayName());
        payload.put("role", user.getRole());
        payload.put("supplier", supplierPayload);
        return payload;
    }

    @Transactional
    public Map<String, Object> changePassword(AdminUserRow user, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        String oldPassword = PartnerBody.requiredString(body, "old_password", 1, 128);
        String newPassword = PartnerBody.requiredString(body, "new_password", 8, 128);
        if (!PasswordHasher.verify(oldPassword, user.getPasswordHash())) {
            throw PartnerErrors.badRequest("原密码不正确");
        }
        if (newPassword.equals(oldPassword)) {
            throw PartnerErrors.unprocessable("新密码不能与原密码相同");
        }
        // Revoke *before* committing the new password: a Redis outage must fail the
        // request instead of silently leaving the old credential alive.
        int killed = revokeSessions(user.getId());
        adminUsers.updatePassword(user.getId(), PasswordHasher.hash(newPassword));
        return Map.of("revoked_sessions", killed);
    }

    public Map<String, Object> logout(String token) {
        try {
            redis.delete(SessionService.ADMIN_PREFIX + token);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("会话存储不可用");
        }
        return Map.of("ok", true);
    }

    /** Drop every live session belonging to one staff account (same scan as Python). */
    private int revokeSessions(long userId) {
        int killed = 0;
        try {
            for (String key : redis.scan(SessionService.ADMIN_PREFIX + "*")) {
                if (String.valueOf(userId).equals(redis.get(key))) {
                    redis.delete(key);
                    killed++;
                }
            }
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("会话存储不可用");
        }
        return killed;
    }
}
