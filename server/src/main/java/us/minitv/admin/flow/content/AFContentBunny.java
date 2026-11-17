package us.minitv.admin.flow.content;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import us.minitv.common.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bunny gateways, ported from {@code backend/app/modules/common/bunny.py}.
 *
 * <p>{@code BUNNY_MODE=fake} (the default, and what the dev {@code .env} pins)
 * keeps every call offline: the admin console uploads to the local dev TUS
 * receiver and playback URLs are signed with local secrets. {@code real} mode
 * talks to the Bunny Stream (video objects, TUS authorization, player tokens)
 * and Storage (covers, subtitles) HTTP APIs, matching the Python gateway.</p>
 */
@Component
public class AFContentBunny {

    static final String STREAM_API = "https://video.bunnycdn.com";
    static final String STREAM_TUS_ENDPOINT = STREAM_API + "/tusupload";
    static final String ACCOUNT_API = "https://api.bunny.net";
    static final int DEFAULT_TTL_SECONDS = 300;

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    private final Environment environment;
    private final HttpClient client;

    public AFContentBunny(Environment environment) {
        this.environment = environment;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** {@code bunny.mode()}: {@code BUNNY_MODE} lower-cased, {@code fake} when blank. */
    public String mode() {
        String raw = env("BUNNY_MODE", "fake");
        String normalised = raw == null ? "" : raw.trim().toLowerCase();
        return normalised.isEmpty() ? "fake" : normalised;
    }

    /** {@code bunny.live()}: true only in real mode. */
    public boolean live() {
        return "real".equals(mode());
    }

    public String env(String name, String fallback) {
        String value = environment.getProperty(name);
        return value == null ? fallback : value;
    }

    private String require(String name) {
        String value = environment.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new BunnyError("BUNNY_MODE=real but " + name + " is not configured");
        }
        return value;
    }

    /** {@code bunny.BunnyError}; the routes surface its message as detail. */
    public static class BunnyError extends RuntimeException {
        public BunnyError(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Callback signatures (fake mode TUS auth + /v1/bunny/callback)
    // ------------------------------------------------------------------

    /** {@code upload_service.callback_secret()}. */
    public byte[] callbackSecret() {
        String secret = env("BUNNY_CALLBACK_SECRET", "local-dev-callback");
        return (secret == null ? "local-dev-callback" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code upload_service.sign_callback(body)}: HMAC-SHA256 hex digest. */
    public String signCallback(byte[] body) {
        return hmacHex(callbackSecret(), body);
    }

    // ------------------------------------------------------------------
    // Stream: video objects, TUS authorization, player tokens
    // ------------------------------------------------------------------

    /** {@code create_stream_video}: returns the new GUID, retrying transient 5xx. */
    public String createStreamVideo(String title) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        String payload = Json.write(Map.of("title", title == null || title.isEmpty() ? "untitled" : title));
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        String lastError = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResult result = http("POST", STREAM_API + "/library/" + libraryId + "/videos",
                    Map.of("AccessKey", apiKey, "Content-Type", "application/json"), data, 30);
            if (result.status() == 200 || result.status() == 201) {
                JsonNode node = parse(result.body());
                String guid = node == null ? "" : node.path("guid").asText("");
                if (guid.isEmpty()) {
                    throw new BunnyError("create video returned no guid: " + repr(result.body()));
                }
                return guid;
            }
            lastError = result.status() + ": " + repr(result.body());
            if (!RETRYABLE_STATUS.contains(result.status())) {
                break;
            }
            if (attempt < 2) {
                sleep(500L * (1L << attempt));
            }
        }
        throw new BunnyError("create video failed (" + lastError + ")");
    }

    /** {@code stream_video}: the Bunny Stream video object. */
    public JsonNode streamVideo(String videoId) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        String url = STREAM_API + "/library/" + libraryId + "/videos/" + videoId;
        String lastError = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResult result = http("GET", url,
                    Map.of("AccessKey", apiKey, "Accept", "application/json"), null, 30);
            if (result.status() == 200) {
                JsonNode node = parse(result.body());
                if (node == null) {
                    throw new BunnyError("get video failed (unreadable body)");
                }
                return node;
            }
            lastError = result.status() + ": " + repr(result.body());
            if (!RETRYABLE_STATUS.contains(result.status())) {
                break;
            }
            if (attempt < 2) {
                sleep(500L * (1L << attempt));
            }
        }
        throw new BunnyError("get video failed (" + lastError + ")");
    }

    /** {@code list_stream_videos}: every video in the library, keyed by GUID. */
    public Map<String, JsonNode> listStreamVideos() {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        Map<String, JsonNode> found = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            String lastError = "";
            JsonNode data = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                String url = STREAM_API + "/library/" + libraryId
                        + "/videos?page=" + page + "&itemsPerPage=1000";
                HttpResult result = http("GET", url,
                        Map.of("AccessKey", apiKey, "Accept", "application/json"), null, 90);
                if (result.status() == 200) {
                    data = parse(result.body());
                    break;
                }
                lastError = result.status() + ": " + repr(result.body());
                if (!RETRYABLE_STATUS.contains(result.status()) || attempt == 2) {
                    throw new BunnyError("list videos failed (" + lastError + ")");
                }
                sleep(500L * (1L << attempt));
            }
            if (data == null) {
                throw new BunnyError("list videos failed (unreadable body)");
            }
            JsonNode items = data.path("items");
            int itemCount = items.isArray() ? items.size() : 0;
            for (int index = 0; index < itemCount; index++) {
                JsonNode item = items.get(index);
                String guid = item.path("guid").asText("");
                if (!guid.isEmpty()) {
                    found.put(guid, item);
                }
            }
            int total = data.path("totalItems").asInt(0);
            if (itemCount == 0 || page * 1000 >= total) {
                return found;
            }
            page++;
        }
    }

    /** {@code bunny.tus_headers}: the four headers every TUS request must carry. */
    public Map<String, String> tusHeaders(String videoId, int ttl) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        long expire = Instant.now().getEpochSecond() + ttl;
        String signature = sha256Hex(libraryId + apiKey + expire + videoId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationSignature", signature);
        headers.put("AuthorizationExpire", String.valueOf(expire));
        headers.put("VideoId", videoId);
        headers.put("LibraryId", libraryId);
        return headers;
    }

    /** {@code stream_embed_url}: signed Bunny Player URLs for the admin preview. */
    public Map<String, Object> streamEmbedUrl(String videoId, int ttl) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String key = require("BUNNY_STREAM_TOKEN_KEY");
        long expires = Instant.now().getEpochSecond() + ttl;
        String token = sha256Hex(key + videoId + expires);
        String query = "token=" + token + "&expires=" + expires;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("token", token);
        signed.put("expires_at", expires);
        signed.put("url", "https://player.mediadelivery.net/play/" + libraryId + "/" + videoId + "?" + query);
        signed.put("embed_url", "https://iframe.mediadelivery.net/embed/" + libraryId + "/" + videoId
                + "?" + query + "&autoplay=true&playsinline=true");
        return signed;
    }

    /** {@code sign_stream_playback}: path-based HLS directory token + URL. */
    public Map<String, Object> signStreamPlayback(String videoId, int ttl) {
        String tokenPath = "/" + videoId + "/";
        Map<String, Object> signed = signDirectory(require("BUNNY_STREAM_TOKEN_KEY"), tokenPath, ttl);
        String encoded = percentEncode(tokenPath);
        String url = "https://" + streamCdnHost() + "/bcdn_token=" + signed.get("token")
                + "&token_path=" + encoded + "&expires=" + signed.get("expires_at")
                + "/" + videoId + "/playlist.m3u8";
        signed.put("url", url);
        signed.put("gen", 0);
        return signed;
    }

    /** {@code sign_image_url}: query-string token for one storage-backed file. */
    public Map<String, Object> signImageUrl(String remotePath, int ttl) {
        String path = "/" + stripLeadingSlashes(remotePath);
        int lastSlash = path.lastIndexOf('/');
        String tokenPath = path.substring(0, lastSlash + 1);
        Map<String, Object> signed = signDirectory(require("BUNNY_IMAGE_TOKEN_KEY"), tokenPath, ttl);
        String encoded = percentEncode(tokenPath);
        String url = "https://" + imageCdnHost() + path
                + "?token=" + signed.get("token") + "&token_path=" + encoded
                + "&expires=" + signed.get("expires_at");
        signed.put("url", url);
        signed.put("gen", 0);
        return signed;
    }

    /** {@code sign_directory}: HMAC directory token valid for everything under {@code tokenPath}. */
    private Map<String, Object> signDirectory(String securityKey, String tokenPath, int ttl) {
        if (!tokenPath.endsWith("/")) {
            throw new IllegalArgumentException("directory token path must end with '/'");
        }
        long expires = Instant.now().getEpochSecond() + ttl;
        String token = advancedToken(securityKey, tokenPath, expires, "token_path=" + tokenPath);
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("token", token);
        signed.put("expires_at", expires);
        signed.put("path", tokenPath);
        return signed;
    }

    /** {@code advanced_token}: HS256-&lt;base64url(HMAC-SHA256(key, path+expires+data))&gt;. */
    private String advancedToken(String securityKey, String signaturePath, long expires, String signingData) {
        byte[] message = (signaturePath + expires + signingData).getBytes(StandardCharsets.UTF_8);
        byte[] digest = hmacRaw(securityKey.getBytes(StandardCharsets.UTF_8), message);
        return "HS256-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    // ------------------------------------------------------------------
    // Storage: cover and subtitle objects
    // ------------------------------------------------------------------

    /** {@code storage_upload}: PUT bytes into the Storage Zone. */
    public void storageUpload(String remotePath, byte[] data, String contentType) {
        String zone = require("BUNNY_STORAGE_ZONE");
        String host = require("BUNNY_STORAGE_HOST");
        String password = require("BUNNY_STORAGE_PASSWORD");
        String url = "https://" + host + "/" + zone + "/" + stripLeadingSlashes(remotePath);
        HttpResult result = http("PUT", url,
                Map.of("AccessKey", password, "Content-Type", contentType == null ? "application/octet-stream" : contentType),
                data, 60);
        if (result.status() != 200 && result.status() != 201) {
            throw new BunnyError("storage upload failed (" + result.status() + "): " + repr(result.body()));
        }
    }

    /** {@code storage_delete}: a missing object is not an error. */
    public void storageDelete(String remotePath) {
        String zone = require("BUNNY_STORAGE_ZONE");
        String host = require("BUNNY_STORAGE_HOST");
        String password = require("BUNNY_STORAGE_PASSWORD");
        String url = "https://" + host + "/" + zone + "/" + stripLeadingSlashes(remotePath);
        HttpResult result = http("DELETE", url, Map.of("AccessKey", password), null, 30);
        if (result.status() != 200 && result.status() != 204 && result.status() != 404) {
            throw new BunnyError("storage delete failed (" + result.status() + "): " + repr(result.body()));
        }
    }

    /** {@code image_url}: plain (unsigned) image CDN URL for a storage object. */
    public String imageUrl(String remotePath) {
        return "https://" + imageCdnHost() + "/" + stripLeadingSlashes(remotePath);
    }

    public String imageCdnHost() {
        return require("BUNNY_IMAGE_CDN_HOST");
    }

    public String streamCdnHost() {
        return require("BUNNY_STREAM_CDN_HOST");
    }

    /** {@code purge_url}: drop one URL (wildcards allowed) from the edge cache. */
    public void purgeUrl(String url) {
        String apiKey = require("BUNNY_API_KEY");
        String target = ACCOUNT_API + "/purge?url=" + percentEncode(url) + "&async=true";
        HttpResult result = http("POST", target, Map.of("AccessKey", apiKey), null, 30);
        if (result.status() >= 300) {
            throw new BunnyError("purge failed (" + result.status() + ") for " + url + ": " + repr(result.body()));
        }
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private record HttpResult(int status, byte[] body) {
    }

    private HttpResult http(String method, String url, Map<String, String> headers, byte[] data, int timeoutSeconds) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            if (data == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(data));
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.statusCode(), response.body() == null ? new byte[0] : response.body());
        } catch (IOException ex) {
            throw new BunnyError(method + " " + url + " failed: " + ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BunnyError(method + " " + url + " failed: " + ex);
        }
    }

    private static JsonNode parse(byte[] body) {
        try {
            return Json.mapper().readTree(body);
        } catch (IOException ex) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacHex(byte[] key, byte[] message) {
        return hex(hmacRaw(key, message));
    }

    private static byte[] hmacRaw(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    /** Python {@code urllib.parse.quote(value, safe="")} for the characters Bunny URLs use. */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int value0 = raw & 0xFF;
            char ch = (char) value0;
            boolean safe = (value0 >= 'A' && value0 <= 'Z')
                    || (value0 >= 'a' && value0 <= 'z')
                    || (value0 >= '0' && value0 <= '9')
                    || ch == '-' || ch == '.' || ch == '_' || ch == '~';
            if (safe) {
                out.append(ch);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((value0 >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(value0 & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String stripLeadingSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    /** Python {@code repr(bytes[:200])}; only used inside upstream-failure messages. */
    private static String repr(byte[] body) {
        if (body == null || body.length == 0) {
            return "b''";
        }
        int length = Math.min(body.length, 200);
        String text = new String(body, 0, length, StandardCharsets.UTF_8);
        return "b'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
