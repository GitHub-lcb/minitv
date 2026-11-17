package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import us.minitv.common.Json;
import us.minitv.security.PasswordHasher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Bunny (bunny.net) gateway for the partner surface, ported from
 * {@code app.modules.common.bunny}.
 *
 * <p>Two modes selected by {@code BUNNY_MODE}: {@code fake} (default, local
 * stubs) and {@code real} (calls the Bunny APIs). Only the endpoints the
 * supplier surface needs are implemented: Stream video creation, TUS
 * authorization headers, player embed signing, Storage upload/delete, image
 * CDN URLs and cache purge.
 */
@Component
public class PartnerBunny {

    public static final String STREAM_API = "https://video.bunnycdn.com";
    public static final String STREAM_TUS_ENDPOINT = STREAM_API + "/tusupload";
    public static final String ACCOUNT_API = "https://api.bunny.net";

    private static final int[] RETRYABLE_STATUS = {429, 500, 502, 503, 504};
    private static final int ATTEMPTS = 3;

    private final Environment environment;
    private final HttpClient client;

    public PartnerBunny(Environment environment) {
        this.environment = environment;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public record HttpResult(int status, byte[] body) {
    }

    // ---- configuration ----

    /** {@code os.environ.get("BUNNY_MODE", "fake").strip().lower() or "fake"}. */
    public String mode() {
        String value = env("BUNNY_MODE").toLowerCase();
        return value.isEmpty() ? "fake" : value;
    }

    /** True when the gateways should talk to the real Bunny APIs. */
    public boolean live() {
        return "real".equals(mode());
    }

    private String env(String name) {
        String value = System.getenv(name);
        if (value == null) {
            value = environment.getProperty(name);
        }
        return value == null ? "" : value.strip();
    }

    private String require(String name) {
        String value = env(name);
        if (value.isEmpty()) {
            throw new PartnerBunnyException("BUNNY_MODE=real but " + name + " is not configured");
        }
        return value;
    }

    // ---- HTTP ----

    private HttpResult http(String method, String url, Map<String, String> headers, byte[] data, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
            if (headers != null) {
                headers.forEach(builder::header);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                builder.method(method, data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(data));
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.statusCode(), response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PartnerBunnyException(method + " " + url + " failed: " + ex);
        } catch (Exception ex) {
            throw new PartnerBunnyException(method + " " + url + " failed: " + ex);
        }
    }

    private static boolean retryable(int status) {
        for (int candidate : RETRYABLE_STATUS) {
            if (candidate == status) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String snippet(byte[] body) {
        if (body == null) {
            return "''";
        }
        String text = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8);
        return "'" + text + "'";
    }

    private static JsonNode parse(byte[] body) {
        try {
            return Json.mapper().readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    // ---- Stream: video object creation + TUS upload authorization ----

    /**
     * Create a Stream video object and return its GUID (real mode).
     * Retries transient upstream failures (rate limits and 5xx) with a small
     * exponential backoff, exactly like the Python gateway.
     */
    public String createStreamVideo(String title) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        String finalTitle = PartnerText.truthy(title) ? title : "untitled";
        byte[] payload = Json.write(Map.of("title", finalTitle)).getBytes(StandardCharsets.UTF_8);
        String lastError = "";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HttpResult result = http("POST", STREAM_API + "/library/" + libraryId + "/videos",
                    Map.of("AccessKey", apiKey, "Content-Type", "application/json"),
                    payload, Duration.ofSeconds(30));
            if (result.status() == 200 || result.status() == 201) {
                JsonNode node = parse(result.body());
                String guid = node == null ? "" : node.path("guid").asText("");
                if (guid.isEmpty()) {
                    throw new PartnerBunnyException("create video returned no guid: " + snippet(result.body()));
                }
                return guid;
            }
            lastError = result.status() + ": " + snippet(result.body());
            if (!retryable(result.status())) {
                break;
            }
            if (attempt < ATTEMPTS - 1) {
                sleep(500L * (1L << attempt));
            }
        }
        throw new PartnerBunnyException("create video failed (" + lastError + ")");
    }

    /** Return the Bunny Stream video object, retrying transient failures. */
    public JsonNode streamVideo(String videoId) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        String lastError = "";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HttpResult result = http("GET", STREAM_API + "/library/" + libraryId + "/videos/" + videoId,
                    Map.of("AccessKey", apiKey, "Accept", "application/json"), null, Duration.ofSeconds(30));
            if (result.status() == 200) {
                JsonNode node = parse(result.body());
                return node == null ? Json.mapper().createObjectNode() : node;
            }
            lastError = result.status() + ": " + snippet(result.body());
            if (!retryable(result.status())) {
                break;
            }
            if (attempt < ATTEMPTS - 1) {
                sleep(500L * (1L << attempt));
            }
        }
        throw new PartnerBunnyException("get video failed (" + lastError + ")");
    }

    /**
     * Authorization headers the client must send on every TUS request.
     * Bunny expects the same four headers on the create (POST) and on every
     * PATCH of the upload session.
     */
    public Map<String, String> tusHeaders(String videoId, int ttl) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        long expire = System.currentTimeMillis() / 1000L + ttl;
        String signature = PasswordHasher.sha256Hex(libraryId + apiKey + expire + (videoId == null ? "" : videoId));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationSignature", signature);
        headers.put("AuthorizationExpire", String.valueOf(expire));
        headers.put("VideoId", videoId == null ? "" : videoId);
        headers.put("LibraryId", libraryId);
        return headers;
    }

    /**
     * Signed Bunny Player URLs for a supplier preview. Embed-view token
     * authentication hashes {@code token_key + video_id + expires} with
     * SHA-256 and returns hex.
     */
    public Map<String, Object> streamEmbedUrl(String videoId, int ttl) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String key = require("BUNNY_STREAM_TOKEN_KEY");
        long expires = System.currentTimeMillis() / 1000L + ttl;
        String token = PasswordHasher.sha256Hex(key + (videoId == null ? "" : videoId) + expires);
        String query = "token=" + token + "&expires=" + expires;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("token", token);
        signed.put("expires_at", expires);
        signed.put("url", "https://player.mediadelivery.net/play/" + libraryId + "/" + videoId + "?" + query);
        signed.put("embed_url", "https://iframe.mediadelivery.net/embed/" + libraryId + "/" + videoId
                + "?" + query + "&autoplay=true&playsinline=true");
        return signed;
    }

    // ---- Storage: cover files ----

    /** Upload bytes to the Storage Zone (primary region endpoint). */
    public void storageUpload(String remotePath, byte[] data, String contentType) {
        String zone = require("BUNNY_STORAGE_ZONE");
        String host = require("BUNNY_STORAGE_HOST");
        String password = require("BUNNY_STORAGE_PASSWORD");
        HttpResult result = http("PUT", "https://" + host + "/" + zone + "/" + trimLeadingSlash(remotePath),
                Map.of("AccessKey", password, "Content-Type", contentType),
                data, Duration.ofSeconds(60));
        if (result.status() != 200 && result.status() != 201) {
            throw new PartnerBunnyException("storage upload failed (" + result.status() + "): "
                    + snippet(result.body()));
        }
    }

    /** Delete one Storage Zone object. A missing object is not an error. */
    public void storageDelete(String remotePath) {
        String zone = require("BUNNY_STORAGE_ZONE");
        String host = require("BUNNY_STORAGE_HOST");
        String password = require("BUNNY_STORAGE_PASSWORD");
        HttpResult result = http("DELETE", "https://" + host + "/" + zone + "/" + trimLeadingSlash(remotePath),
                Map.of("AccessKey", password), null, Duration.ofSeconds(30));
        if (result.status() != 200 && result.status() != 204 && result.status() != 404) {
            throw new PartnerBunnyException("storage delete failed (" + result.status() + "): "
                    + snippet(result.body()));
        }
    }

    /** Plain (unsigned) image CDN URL for a storage object. */
    public String imageUrl(String remotePath) {
        return "https://" + require("BUNNY_IMAGE_CDN_HOST") + "/" + trimLeadingSlash(remotePath);
    }

    /** Purge one URL (wildcards allowed) from the Bunny edge cache. */
    public void purgeUrl(String url) {
        String encoded;
        try {
            encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            encoded = url;
        }
        HttpResult result = http("POST", ACCOUNT_API + "/purge?url=" + encoded + "&async=true",
                Map.of("AccessKey", require("BUNNY_API_KEY")), null, Duration.ofSeconds(30));
        if (result.status() >= 300) {
            throw new PartnerBunnyException("purge failed (" + result.status() + ") for " + url + ": "
                    + snippet(result.body()));
        }
    }

    private static String trimLeadingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    // ---- fake-mode callback signature ----

    /** HMAC-SHA256 used by the fake upload receiver, same secret and encoding as Python. */
    public String signCallback(byte[] body) {
        String secret = env("BUNNY_CALLBACK_SECRET");
        if (secret.isEmpty()) {
            secret = "local-dev-callback";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new PartnerBunnyException("callback signing unavailable: " + ex);
        }
    }
}
