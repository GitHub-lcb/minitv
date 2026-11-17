package us.minitv.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.common.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health - same contract as the Python local health app.
 *
 * The Python version swaps the DB version key to ``mysql`` now that PostgreSQL
 * is gone; a dependency failure surfaces as a 500 like an unhandled FastAPI
 * exception did.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;

    public HealthController(JdbcTemplate jdbc, RedisConnectionFactory redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            String version = jdbc.queryForObject("SELECT VERSION()", String.class);
            String redisVersion;
            try (RedisConnection connection = redis.getConnection()) {
                connection.ping();
                redisVersion = String.valueOf(connection.serverCommands().info("server").get("redis_version"));
            }
            payload.put("status", "ok");
            payload.put("env", us.minitv.common.Env.get("APP_ENV", "local"));
            payload.put("mysql", version);
            payload.put("redis", redisVersion);
            return payload;
        } catch (Exception ex) {
            throw ApiException.of(500, "Internal Server Error");
        }
    }
}
