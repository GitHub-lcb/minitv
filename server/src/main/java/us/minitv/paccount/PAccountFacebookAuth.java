package us.minitv.paccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import us.minitv.common.ApiException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Facebook Login token verification, ported from account/facebook.py.
 *
 * <p>Two shapes of proof reach this backend: an OpenID Connect {@code id_token}
 * from Limited Login (a JWT signed by Facebook) or an opaque {@code access_token}
 * from the Android / H5 SDK, which is exchanged against debug_token and /me
 * first. Either way the token has to belong to {@code FACEBOOK_APP_ID}.</p>
 */
@Service
public class PAccountFacebookAuth {

    private static final String FACEBOOK_JWKS_URL =
            "https://www.facebook.com/.well-known/oauth/openid/jwks/";
    private static final String FACEBOOK_ISSUER = "https://www.facebook.com";
    private static final String GRAPH_BASE = "https://graph.facebook.com/v21.0";
    /** picture.type(large) is the largest the default profile edge returns. */
    private static final String GRAPH_FIELDS = "id,name,email,picture.type(large)";
    private static final PAccountJwtSupport.Messages MESSAGES = new PAccountJwtSupport.Messages(
            "facebook keys are unreachable",
            "facebook published no signing keys",
            "facebook signing key not found",
            "invalid facebook token");

    @Value("${FACEBOOK_APP_ID:}")
    private String facebookAppId;

    @Value("${FACEBOOK_APP_SECRET:}")
    private String facebookAppSecret;

    /** Claims used for account linking (account/facebook.py FacebookIdentity). */
    public record Identity(String subject, String email, String name, String picture, String via) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Verify whichever token the client managed to obtain. */
    public Identity verify(String accessToken, String idToken) {
        if (idToken != null && !idToken.isEmpty()) {
            return verifyIdToken(idToken);
        }
        if (accessToken != null && !accessToken.isEmpty()) {
            return verifyAccessToken(accessToken);
        }
        throw ApiException.of(401, "facebook token is missing");
    }

    private String appId() {
        return facebookAppId == null ? "" : facebookAppId.strip();
    }

    private String appSecret() {
        return facebookAppSecret == null ? "" : facebookAppSecret.strip();
    }

    /** Limited Login id_token: like Google's, the claims are trusted once signed. */
    private Identity verifyIdToken(String token) {
        String audience = appId();
        if (audience.isEmpty()) {
            throw ApiException.of(401, "facebook is not configured");
        }
        JsonNode claims;
        try {
            claims = PAccountJwtSupport.verify(token, List.of(audience), List.of(FACEBOOK_ISSUER),
                    FACEBOOK_JWKS_URL, "minitv-facebook", MESSAGES);
        } catch (PAccountJwtSupport.JwtException ex) {
            throw ApiException.of(401, ex.getMessage());
        }
        JsonNode subjectNode = claims.get("sub");
        if (subjectNode == null || !subjectNode.isTextual() || subjectNode.asText().isEmpty()) {
            throw ApiException.of(401, "facebook account has no subject");
        }
        JsonNode pictureNode = claims.get("picture");
        String picture = pictureNode != null && pictureNode.isTextual()
                ? PAccountText.truncate(pictureNode.asText(), 500) : "";
        return new Identity(subjectNode.asText(), cleanEmail(claims.get("email")),
                cleanName(claims.get("name")), picture, "limited_login");
    }

    /** Exchange an opaque access token for the profile it was minted for. */
    private Identity verifyAccessToken(String token) {
        String app = appId();
        String secret = appSecret();
        if (app.isEmpty() || secret.isEmpty()) {
            throw ApiException.of(401, "facebook is not configured");
        }
        Map<String, String> debugParams = new LinkedHashMap<>();
        debugParams.put("input_token", token);
        debugParams.put("access_token", app + "|" + secret);
        JsonNode debug = requestJson(GRAPH_BASE + "/debug_token", debugParams);
        JsonNode data = debug.get("data") != null && debug.get("data").isObject()
                ? debug.get("data") : MAPPER.createObjectNode();
        if (!pyTruthy(data.get("is_valid"))) {
            throw ApiException.of(401, "facebook access token is invalid");
        }
        if (!pyStr(data.get("app_id")).equals(app)) {
            throw ApiException.of(401, "facebook token was issued for another app");
        }
        Map<String, String> profileParams = new LinkedHashMap<>();
        profileParams.put("fields", GRAPH_FIELDS);
        profileParams.put("access_token", token);
        JsonNode profile = requestJson(GRAPH_BASE + "/me", profileParams);
        JsonNode subjectNode = profile.get("id");
        if (subjectNode == null || !subjectNode.isTextual() || subjectNode.asText().isEmpty()) {
            throw ApiException.of(401, "facebook account has no id");
        }
        String picture = "";
        JsonNode rawPicture = profile.get("picture");
        if (rawPicture != null && rawPicture.isObject()) {
            JsonNode pictureData = rawPicture.get("data");
            if (pictureData != null && pictureData.isObject()) {
                JsonNode url = pictureData.get("url");
                if (url != null && url.isTextual()) {
                    picture = PAccountText.truncate(url.asText(), 500);
                }
            }
        }
        return new Identity(subjectNode.asText(), cleanEmail(profile.get("email")),
                cleanName(profile.get("name")), picture, "graph");
    }

    /** Python {@code _request_json}: Graph errors are client problems, never a 500. */
    private JsonNode requestJson(String path, Map<String, String> params) {
        StringBuilder url = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "minitv-facebook")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ApiException.of(401, "facebook is unreachable");
        } catch (Exception ex) {
            throw ApiException.of(401, "facebook is unreachable");
        }
        if (response.statusCode() >= 400) {
            // Graph puts the reason in an OAuth-shaped error body; a bad or
            // expired token is a client problem, never a 500 of ours.
            throw ApiException.of(401, "facebook rejected the token");
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw ApiException.of(401, "facebook is unreachable");
        }
        if (payload == null || !payload.isObject()) {
            throw ApiException.of(401, "facebook returned an unexpected payload");
        }
        if (payload.has("error")) {
            throw ApiException.of(401, "facebook rejected the token");
        }
        return payload;
    }

    private static String cleanEmail(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String email = value.asText().strip().toLowerCase(Locale.ROOT);
        return email.contains("@") ? email : null;
    }

    private static String cleanName(JsonNode value) {
        return value != null && value.isTextual()
                ? PAccountText.truncate(value.asText().strip(), 64) : "";
    }

    /** Python truthiness of a JSON value. */
    private static boolean pyTruthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0.0;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        return node.size() > 0;
    }

    /** Python {@code str(value)} for the debug_token app_id comparison. */
    private static String pyStr(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "None";
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "True" : "False";
        }
        return node.asText();
    }
}
