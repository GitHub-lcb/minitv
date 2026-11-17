package us.minitv.common;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Environment lookups for code that cannot inject Spring beans (static helpers).
 *
 * The Python service loads the repository .env into {@code os.environ}; the Java
 * service loads the same file through {@code spring.config.import}, so the
 * values live in the Spring Environment (and OS variables still win). Reading
 * straight from {@code System.getenv()} would miss locally-run .env values.
 */
public final class Env {

    private static volatile Environment environment;

    private Env() {
    }

    public static void bind(Environment value) {
        environment = value;
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String fallback) {
        Environment env = environment;
        if (env != null) {
            String value = env.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }

    public static int getInt(String key, int fallback) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Component
    static class Binder implements EnvironmentAware {
        @Override
        public void setEnvironment(Environment value) {
            Env.bind(value);
        }
    }
}
