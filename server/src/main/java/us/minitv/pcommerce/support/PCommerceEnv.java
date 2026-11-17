package us.minitv.pcommerce.support;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Environment access for the commerce module.
 *
 * <p>The Python service reads {@code os.environ}, populated from the repository
 * root {@code .env} by dotenv. The Java base imports the same file as Spring
 * properties, so {@link Environment#getProperty(String)} sees the identical
 * values (OS environment variables still win).</p>
 */
@Component
public class PCommerceEnv {

    private final Environment environment;

    public PCommerceEnv(Environment environment) {
        this.environment = environment;
    }

    /** os.environ.get(name) - null when the variable is absent. */
    public String raw(String name) {
        return environment.getProperty(name);
    }

    /** os.environ.get(name, fallback) - an explicitly empty variable stays empty. */
    public String value(String name, String fallback) {
        String raw = raw(name);
        return raw == null ? fallback : raw;
    }

    /** os.environ.get(name, "").strip() */
    public String trimmed(String name, String fallback) {
        String raw = raw(name);
        return raw == null ? fallback : raw.trim();
    }

    /** Python str.rstrip("/") */
    public static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    public byte[] videoSecret() {
        return value("PLAYBACK_TOKEN_KEY", "local-playback-token").getBytes(StandardCharsets.UTF_8);
    }

    public byte[] imageSecret() {
        return value("IMAGE_TOKEN_KEY", "local-image-token").getBytes(StandardCharsets.UTF_8);
    }

    public String cdnBase() {
        return stripTrailingSlashes(value("PLAYBACK_CDN_BASE", "https://cdn.local"));
    }

    public byte[] payWebhookSecret() {
        return value("PAY_WEBHOOK_SECRET", "local-pay-webhook").getBytes(StandardCharsets.UTF_8);
    }

    public byte[] bunnyCallbackSecret() {
        return value("BUNNY_CALLBACK_SECRET", "local-dev-callback").getBytes(StandardCharsets.UTF_8);
    }

    /** bunny.mode(): BUNNY_MODE trimmed/lower-cased, empty falls back to "fake". */
    public String bunnyMode() {
        String mode = value("BUNNY_MODE", "fake").trim().toLowerCase(Locale.ROOT);
        return mode.isEmpty() ? "fake" : mode;
    }

    public boolean bunnyLive() {
        return "real".equals(bunnyMode());
    }

    public boolean memberOnly1080() {
        return "true".equalsIgnoreCase(value("MEMBER_ONLY_1080", "false"));
    }

    public String h5Base() {
        return stripTrailingSlashes(value("H5_PUBLIC_BASE_URL", "https://h5.minitv.us"));
    }

    public String stripeSecretKey() {
        return value("STRIPE_SECRET_KEY", "").trim();
    }

    public String stripeWebhookSecret() {
        return value("STRIPE_WEBHOOK_SECRET", "").trim();
    }
}
