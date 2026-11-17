package us.minitv.pcommerce.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import us.minitv.common.ApiException;
import us.minitv.common.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Stripe REST client plus webhook signature verification, ported from
 * {@code billing.service._stripe_request} / {@code verify_stripe_signature}.
 *
 * <p>Failures surface as FastAPI-shaped {@link ApiException}s so controllers
 * need no translation: 503 when unconfigured, 502 for rejected/unreachable
 * upstream calls, 400/401 for bad webhook signatures.</p>
 */
@Component
public class PCommerceStripeClient {

    private static final String STRIPE_API = "https://api.stripe.com/v1";
    private static final int WEBHOOK_TOLERANCE_SECONDS = 300;

    private final PCommerceEnv env;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public PCommerceStripeClient(PCommerceEnv env) {
        this.env = env;
    }

    /** stripe_secret(): 503 when no key is configured. */
    public String secret() {
        String key = env.stripeSecretKey();
        if (key.isEmpty()) {
            throw new ApiException(503, "stripe is not configured");
        }
        return key;
    }

    /**
     * stripe request: form-encoded POST/POST/DELETE. An empty body turns a POST
     * into a GET (Stripe has no bodyless POST); an explicit DELETE stays DELETE.
     */
    public JsonNode request(String path, Map<String, String> data, String method) {
        String payload = data == null ? null : formEncode(data);
        String verb = method;
        if (payload == null && "POST".equals(method)) {
            verb = "GET";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(STRIPE_API + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secret())
                .header("User-Agent", "minitv-billing/1.0");
        if (payload == null) {
            builder.method(verb, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(verb, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new ApiException(502, "stripe is unreachable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "stripe is unreachable");
        }
        if (response.statusCode() >= 400) {
            String message = "";
            try {
                JsonNode detail = Json.mapper().readTree(response.body());
                JsonNode error = detail == null ? null : detail.get("error");
                JsonNode text = error == null ? null : error.get("message");
                if (text != null && !text.isNull()) {
                    message = text.asText();
                }
            } catch (IOException ignored) {
                // never mask the HTTP status
            }
            throw new ApiException(502,
                    "stripe rejected the request: " + (message.isEmpty()
                            ? String.valueOf(response.statusCode()) : message));
        }
        try {
            return Json.mapper().readTree(response.body());
        } catch (IOException ex) {
            // Python treats a non-JSON success body as unreachable (ValueError).
            throw new ApiException(502, "stripe is unreachable");
        }
    }

    /** verify_stripe_signature: Stripe-Signature t=...,v1=... over "&lt;t&gt;.&lt;body&gt;". */
    public void verifySignature(byte[] body, String header) {
        String secret = env.stripeWebhookSecret();
        if (secret.isEmpty()) {
            throw new ApiException(503, "stripe webhook is not configured");
        }
        List<Long> stamps = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        String rawHeader = header == null ? "" : header;
        for (String part : rawHeader.split(",", -1)) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            if ("t".equals(key)) {
                try {
                    stamps.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                    // Python skips unparsable timestamps
                }
            } else if ("v1".equals(key)) {
                signatures.add(value);
            }
        }
        if (stamps.isEmpty() || signatures.isEmpty()) {
            throw new ApiException(400, "missing stripe signature");
        }
        long latest = Collections.max(stamps);
        double now = System.currentTimeMillis() / 1000.0;
        if (Math.abs(now - latest) > WEBHOOK_TOLERANCE_SECONDS) {
            throw new ApiException(400, "stripe signature expired");
        }
        byte[] prefix = (latest + ".").getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        String expected = PCommerceCrypto.hmacSha256Hex(secret.getBytes(StandardCharsets.UTF_8), message);
        for (String candidate : signatures) {
            if (PCommerceCrypto.constantTimeEquals(expected, candidate)) {
                return;
            }
        }
        throw new ApiException(401, "bad stripe signature");
    }

    private static String formEncode(Map<String, String> data) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            // Python urlencode uses quote_plus: percent-encoding with '+' for space.
            out.append(formComponent(entry.getKey()))
                    .append('=')
                    .append(formComponent(entry.getValue()));
        }
        return out.toString();
    }

    private static String formComponent(String value) {
        return PCommerceCrypto.percentEncode(value).replace("%20", "+");
    }

    /** A mutable, insertion-ordered form map (Stripe's fields have no ordering needs). */
    public static Map<String, String> form() {
        return new LinkedHashMap<>();
    }
}
