package us.minitv.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the MySQL datasource from the same {@code DATABASE_URL} the Python
 * service uses, so one .env drives both backends.
 *
 * Accepted shapes:
 *   mysql://user:pass@host:port/db
 *   mysql+pymysql://user:pass@host:port/db?charset=utf8mb4
 *   jdbc:mysql://host:port/db?...
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${DATABASE_URL:}")
    private String databaseUrl;

    @Bean
    public DataSource dataSource() {
        String raw = databaseUrl == null ? "" : databaseUrl.trim();
        if (raw.isEmpty()) {
            raw = "mysql://minitv:minitv_local@127.0.0.1:3306/minitv";
        }
        HikariConfig config = new HikariConfig();
        if (raw.startsWith("jdbc:mysql://")) {
            config.setJdbcUrl(raw);
        } else {
            applyUrl(config, raw);
        }
        config.setMaximumPoolSize(12);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setPoolName("minitv-mysql");
        // The product stores UTC datetimes; pin the session zone so NOW() agrees.
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        log.info("MySQL datasource ready: jdbc:mysql://{}:{}/{}", config.getJdbcUrl() == null ? "?" : "…", "", "");
        return new HikariDataSource(config);
    }

    private static void applyUrl(HikariConfig config, String raw) {
        String normalised = raw;
        if (normalised.startsWith("mysql+pymysql://")) {
            normalised = "mysql://" + normalised.substring("mysql+pymysql://".length());
        }
        if (!normalised.startsWith("mysql://")) {
            throw new IllegalArgumentException("DATABASE_URL must be a mysql:// URL, got: " + mask(normalised));
        }
        URI uri = URI.create(normalised);
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 3306;
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/minitv" : uri.getPath();
        String query = uri.getRawQuery();
        StringBuilder jdbc = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port).append(path)
                .append('?')
                .append(query == null || query.isBlank() ? "" : query + "&")
                .append("useUnicode=true&characterEncoding=utf8")
                .append("&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
                .append("&allowPublicKeyRetrieval=true&useSSL=false");
        config.setJdbcUrl(jdbc.toString());

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            String password = colon < 0 ? "" : userInfo.substring(colon + 1);
            config.setUsername(decode(user));
            config.setPassword(decode(password));
        }
        if (config.getUsername() == null) {
            config.setUsername(us.minitv.common.Env.get("MYSQL_USER", "minitv"));
            config.setPassword(us.minitv.common.Env.get("MYSQL_PASSWORD", "minitv_local"));
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String mask(String url) {
        return url == null ? "" : url.replaceAll("//[^/@]+@", "//***@");
    }
}
