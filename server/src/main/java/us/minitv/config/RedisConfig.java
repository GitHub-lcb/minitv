package us.minitv.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Redis connection built from the Python service's {@code REDIS_URL}. */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${REDIS_URL:redis://127.0.0.1:6379/0}")
    private String redisUrl;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        String raw = redisUrl == null || redisUrl.isBlank()
                ? "redis://127.0.0.1:6379/0"
                : redisUrl.trim();
        String normalised = raw.startsWith("redis://") ? raw : "redis://" + raw;
        URI uri = URI.create(normalised);
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        int database = 0;
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            database = Integer.parseInt(path.substring(1));
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(database);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon <= 0 ? null : URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
            String password = colon < 0 ? userInfo : userInfo.substring(colon + 1);
            if (user != null && !user.isEmpty()) {
                config.setUsername(user);
            }
            config.setPassword(RedisPassword.of(URLDecoder.decode(password, StandardCharsets.UTF_8)));
        }
        log.info("Redis connection ready: {}:{}/{}", host, port, database);
        return new LettuceConnectionFactory(config);
    }
}
