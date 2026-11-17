package us.minitv.pcommerce.support;

import org.springframework.stereotype.Component;
import us.minitv.common.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bunny.net gateway (the subset public playback and the upload callback need).
 *
 * <p>Mirrors {@code app/modules/common/bunny.py}: mode {@code fake} signs local
 * CDN URLs against {@code PLAYBACK_CDN_BASE}; mode {@code real} calls Bunny's
 * Stream API and signs Advanced-Token-Authentication URLs.</p>
 */
@Component
public class PCommerceBunny {

    private static final String STREAM_API = "https://video.bunnycdn.com";
    private static final int DEFAULT_TTL_SECONDS = 300;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    private final PCommerceEnv env;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public PCommerceBunny(PCommerceEnv env) {
        this.env = env;
    }

    /** bunny.live() */
    public boolean live() {
        return env.bunnyLive();
    }

    /** bunny._require(): real mode requires the named credential. */
    public String require(String name) {
        String raw = env.raw(name);
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("BUNNY_MODE=real but " + name + " is not configured");
        }
        return value;
    }

    /** bunny.sign_stream_playback: path-based directory token (segments inherit it). */
    public Map<String, Object> signStreamPlayback(String videoId, int ttl) {
        String tokenPath = "/" + videoId + "/";
        Map<String, Object> signed = signDirectory(require("BUNNY_STREAM_TOKEN_KEY"), tokenPath, ttl);
        String encoded = PCommerceCrypto.percentEncode(tokenPath);
        String url = "https://" + streamCdnHost() + "/bcdn_token=" + signed.get("token")
                + "&token_path=" + encoded + "&expires=" + signed.get("expires_at")
                + "/" + videoId + "/playlist.m3u8";
        Map<String, Object> out = new LinkedHashMap<>(signed);
        out.put("url", url);
        out.put("gen", 0);
        return out;
    }

    /** bunny.sign_image_url: query-string token for one storage-backed file. */
    public Map<String, Object> signImageUrl(String remotePath, int ttl) {
        String path = "/" + stripLeadingSlashes(remotePath);
        int slash = path.lastIndexOf('/');
        String tokenPath = path.substring(0, slash) + "/";
        Map<String, Object> signed = signDirectory(require("BUNNY_IMAGE_TOKEN_KEY"), tokenPath, ttl);
        String encoded = PCommerceCrypto.percentEncode(tokenPath);
        String url = "https://" + imageCdnHost() + path + "?token=" + signed.get("token")
                + "&token_path=" + encoded + "&expires=" + signed.get("expires_at");
        Map<String, Object> out = new LinkedHashMap<>(signed);
        out.put("url", url);
        out.put("gen", 0);
        return out;
    }

    /** bunny.stream_video: GET the Stream video object, retrying transient failures. */
    public Map<String, Object> streamVideo(String videoId) {
        String libraryId = require("BUNNY_STREAM_LIBRARY_ID");
        String apiKey = require("BUNNY_STREAM_API_KEY");
        String url = STREAM_API + "/library/" + libraryId + "/videos/" + videoId;
        String lastError = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("AccessKey", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException("GET " + url + " failed: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GET " + url + " interrupted", ex);
            }
            if (response.statusCode() == 200) {
                return Json.readMap(response.body());
            }
            lastError = response.statusCode() + ": " + snippet(response.body());
            if (!RETRYABLE_STATUS.contains(response.statusCode())) {
                break;
            }
            if (attempt < 2) {
                sleepMillis(500L << attempt);
            }
        }
        throw new IllegalStateException("get video failed (" + lastError + ")");
    }

    // --------------------------------------------------------------- internals

    private Map<String, Object> signDirectory(String securityKey, String tokenPath, int ttl) {
        if (!tokenPath.endsWith("/")) {
            throw new IllegalArgumentException("directory token path must end with '/'");
        }
        long expires = PCommerceCrypto.epochSeconds() + ttl;
        String token = advancedToken(securityKey, tokenPath, expires, "token_path=" + tokenPath);
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("token", token);
        signed.put("expires_at", expires);
        signed.put("path", tokenPath);
        return signed;
    }

    private String advancedToken(String securityKey, String signaturePath, long expires, String signingData) {
        String message = signaturePath + expires + signingData;
        byte[] digest = PCommerceCrypto.hmacSha256(
                securityKey.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8));
        return "HS256-" + PCommerceCrypto.base64Url(digest);
    }

    private String streamCdnHost() {
        return require("BUNNY_STREAM_CDN_HOST");
    }

    private String imageCdnHost() {
        return require("BUNNY_IMAGE_CDN_HOST");
    }

    private static String stripLeadingSlashes(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '/') {
            index++;
        }
        return value.substring(index);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
