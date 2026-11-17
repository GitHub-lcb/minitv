package us.minitv.admin.flow.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;
import us.minitv.common.ApiException;
import us.minitv.common.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Stripe REST client, a straight port of billing/service.py
 * {@code _stripe_request} plus the price/provision env naming.
 */
@Component
public class AFFlowOpsStripeClient {

    static final String STRIPE_API = "https://api.stripe.com/v1";

    static final Map<String, String> PRICE_ENV = Map.of(
            "weekly", "STRIPE_PRICE_WEEKLY",
            "monthly", "STRIPE_PRICE_MONTHLY",
            "yearly", "STRIPE_PRICE_YEARLY");

    static final Map<String, String> PRODUCT_ENV = Map.of(
            "weekly", "STRIPE_PRODUCT_WEEKLY",
            "monthly", "STRIPE_PRODUCT_MONTHLY",
            "yearly", "STRIPE_PRODUCT_YEARLY");

    static final Map<String, String> INTERVAL_OF = Map.of(
            "weekly", "week",
            "monthly", "month",
            "yearly", "year");

    static final Map<String, Integer> PLAN_DAYS = Map.of(
            "weekly", 7,
            "monthly", 30,
            "yearly", 365);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    String env(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String value = us.minitv.common.Env.get(name);
        return value == null ? "" : value.trim();
    }

    String secret() {
        String key = env("STRIPE_SECRET_KEY");
        if (key.isEmpty()) {
            throw new ApiException(503, "stripe is not configured");
        }
        return key;
    }

    Map<String, Object> request(String path, Map<String, String> data, String method) {
        String actualMethod = method;
        String payload = data == null || data.isEmpty() ? null : urlEncode(data);
        if (payload == null && "POST".equals(actualMethod)) {
            actualMethod = "GET";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(STRIPE_API + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secret())
                .header("User-Agent", "minitv-billing/1.0");
        if (payload == null) {
            builder.method(actualMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
            builder.method(actualMethod, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "stripe is unreachable");
        } catch (Exception ex) {
            throw new ApiException(502, "stripe is unreachable");
        }
        if (response.statusCode() >= 400) {
            String message = "";
            try {
                Map<String, Object> body = Json.mapper().readValue(response.body(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object error = body.get("error");
                if (error instanceof Map<?, ?> map && map.get("message") != null) {
                    message = String.valueOf(map.get("message"));
                }
            } catch (Exception ignored) {
                message = "";
            }
            throw new ApiException(502, "stripe rejected the request: "
                    + (message.isEmpty() ? String.valueOf(response.statusCode()) : message));
        }
        try {
            Map<String, Object> parsed = Json.mapper().readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            throw new ApiException(502, "stripe is unreachable");
        }
    }

    private static String urlEncode(Map<String, String> data) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(data).entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            out.append('=');
            out.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                    StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
