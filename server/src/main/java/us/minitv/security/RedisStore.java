package us.minitv.security;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** Thin Redis access shared by sessions, verification codes and rate limiting. */
@Component
public class RedisStore {

    private final StringRedisTemplate template;

    public RedisStore(StringRedisTemplate template) {
        this.template = template;
    }

    public String get(String key) {
        return template.opsForValue().get(key);
    }

    public void set(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl);
    }

    public void set(String key, String value) {
        template.opsForValue().set(key, value);
    }

    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        return template.opsForValue().setIfAbsent(key, value, ttl);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(template.delete(key));
    }

    public long increment(String key) {
        Long value = template.opsForValue().increment(key);
        return value == null ? 0 : value;
    }

    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(template.expire(key, ttl));
    }

    public Set<String> scan(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        try (Cursor<String> cursor = template.scan(
                ScanOptions.scanOptions().match(pattern).count(200).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    public void flushDb() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    public boolean ping() {
        String pong = template.getConnectionFactory().getConnection().ping();
        return pong != null;
    }
}
