package us.minitv.security;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import us.minitv.common.ApiException;
import us.minitv.ops.mapper.AdminUserMapper;
import us.minitv.ops.row.AdminUserRow;

import java.time.Duration;
import java.util.Set;

/**
 * Opaque Redis sessions, matching the Python service:
 * end users keep a 30-day token, staff keep an 8-hour token.
 */
@Service
public class SessionService {

    public static final String USER_PREFIX = "minitv_user_session:";
    public static final String ADMIN_PREFIX = "minitv_admin_session:";
    public static final Duration USER_TTL = Duration.ofDays(30);
    public static final Duration ADMIN_TTL = Duration.ofHours(8);

    private final RedisStore redis;
    private final AdminUserMapper adminUsers;

    public SessionService(RedisStore redis, AdminUserMapper adminUsers) {
        this.redis = redis;
        this.adminUsers = adminUsers;
    }

    public String createUserSession(long userId) {
        String token = Tokens.urlSafe(32);
        redis.set(USER_PREFIX + token, String.valueOf(userId), USER_TTL);
        return token;
    }

    /** Null when the token is unknown/expired; 503 when Redis is unreachable. */
    public Long resolveUserId(String token) {
        try {
            String value = redis.get(USER_PREFIX + token);
            return value == null ? null : Long.valueOf(value);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("会话存储不可用（Redis 未连接）");
        }
    }

    public String createAdminSession(long userId) {
        String token = Tokens.urlSafe(32);
        redis.set(ADMIN_PREFIX + token, String.valueOf(userId), ADMIN_TTL);
        return token;
    }

    public AdminUserRow resolveAdmin(String token) {
        String value;
        try {
            value = redis.get(ADMIN_PREFIX + token);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable(
                    "会话存储不可用（Redis 未连接）。请先启动 minitv-redis："
                            + "在仓库根目录执行 docker compose up -d，然后重试。");
        }
        if (value == null) {
            return null;
        }
        AdminUserRow user = adminUsers.findById(Long.parseLong(value));
        if (user == null || !"active".equals(user.getStatus())) {
            return null;
        }
        return user;
    }

    public void logoutAdmin(String token) {
        try {
            redis.delete(ADMIN_PREFIX + token);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("会话存储不可用（Redis 未连接）");
        }
    }

    /** Drop every live session of one staff account (password change / disable). */
    public int revokeAdminSessions(long userId) {
        int killed = 0;
        try {
            Set<String> keys = redis.scan(ADMIN_PREFIX + "*");
            for (String key : keys) {
                if (String.valueOf(userId).equals(redis.get(key))) {
                    redis.delete(key);
                    killed++;
                }
            }
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("会话存储不可用（Redis 未连接）");
        }
        return killed;
    }

    public boolean isRedisUp() {
        try {
            return redis.ping();
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
