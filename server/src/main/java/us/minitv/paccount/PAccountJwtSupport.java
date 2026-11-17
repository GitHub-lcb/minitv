package us.minitv.paccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal RS256 JWT verification with a JWKS cache, matching the PyJWT
 * semantics account/google.py and account/facebook.py relied on:
 * signature over {@code header.payload}, algorithm RS256, issuer whitelist,
 * audience match and exp / iat / nbf windows.
 *
 * <p>The Python service fetched the provider's published keys with urllib and
 * cached them by Cache-Control max-age (floor 60s). Same behaviour here.</p>
 */
public final class PAccountJwtSupport {

    /** Provider-specific error texts; the callers surface them as 401 details. */
    public record Messages(String keysUnreachable, String noSigningKeys,
                           String keyNotFound, String invalidToken) {
    }

    /** Checked failure carrying the Python exception message verbatim. */
    public static class JwtException extends Exception {
        public JwtException(String message) {
            super(message);
        }
    }

    private record CacheEntry(Instant expiresAt, List<JsonNode> keys) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<String, CacheEntry> KEY_CACHE = new ConcurrentHashMap<>();

    private PAccountJwtSupport() {
    }

    /**
     * Verify a compact RS256 JWT and return its claims.
     *
     * @param audiences every audience accepted by this deployment
     * @param issuers   every issuer accepted (Google allows two spellings)
     */
    public static JsonNode verify(String token, List<String> audiences, List<String> issuers,
                                  String jwksUrl, String userAgent, Messages messages) throws JwtException {
        String[] parts = (token == null ? "" : token).split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtException(messages.invalidToken());
        }
        JsonNode header = decodeSegment(parts[0]);
        if (header == null || !"RS256".equals(text(header, "alg"))) {
            throw new JwtException(messages.invalidToken());
        }
        List<JsonNode> keys = fetchKeys(jwksUrl, userAgent, messages);
        JsonNode jwk = null;
        String kid = text(header, "kid");
        for (JsonNode candidate : keys) {
            if (kid != null && kid.equals(text(candidate, "kid"))) {
                jwk = candidate;
                break;
            }
        }
        if (jwk == null) {
            throw new JwtException(messages.keyNotFound());
        }
        RSAPublicKey publicKey = publicKey(jwk, messages);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new JwtException(messages.invalidToken());
        }
        boolean valid;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            valid = verifier.verify(signature);
        } catch (Exception ex) {
            throw new JwtException(messages.invalidToken());
        }
        if (!valid) {
            throw new JwtException(messages.invalidToken());
        }
        JsonNode claims = decodeSegment(parts[1]);
        if (claims == null || !claims.isObject()) {
            throw new JwtException(messages.invalidToken());
        }
        validateClaims(claims, audiences, issuers, messages);
        return claims;
    }

    private static void validateClaims(JsonNode claims, List<String> audiences, List<String> issuers,
                                       Messages messages) throws JwtException {
        long now = Instant.now().getEpochSecond();
        JsonNode exp = claims.get("exp");
        if (exp != null && numeric(exp, messages) <= now) {
            throw new JwtException(messages.invalidToken());
        }
        JsonNode iat = claims.get("iat");
        if (iat != null && numeric(iat, messages) > now) {
            throw new JwtException(messages.invalidToken());
        }
        JsonNode nbf = claims.get("nbf");
        if (nbf != null && numeric(nbf, messages) > now) {
            throw new JwtException(messages.invalidToken());
        }
        JsonNode aud = claims.get("aud");
        if (aud == null) {
            throw new JwtException(messages.invalidToken());
        }
        List<String> audienceClaims = new ArrayList<>();
        if (aud.isTextual()) {
            audienceClaims.add(aud.asText());
        } else if (aud.isArray()) {
            for (JsonNode item : aud) {
                if (!item.isTextual()) {
                    throw new JwtException(messages.invalidToken());
                }
                audienceClaims.add(item.asText());
            }
        } else {
            throw new JwtException(messages.invalidToken());
        }
        boolean audienceMatches = false;
        for (String audience : audiences) {
            if (audienceClaims.contains(audience)) {
                audienceMatches = true;
                break;
            }
        }
        if (!audienceMatches) {
            throw new JwtException(messages.invalidToken());
        }
        String issuer = text(claims, "iss");
        if (issuer == null || !issuers.contains(issuer)) {
            throw new JwtException(messages.invalidToken());
        }
    }

    private static long numeric(JsonNode node, Messages messages) throws JwtException {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ex) {
                throw new JwtException(messages.invalidToken());
            }
        }
        throw new JwtException(messages.invalidToken());
    }

    private static RSAPublicKey publicKey(JsonNode jwk, Messages messages) throws JwtException {
        String modulus = text(jwk, "n");
        String exponent = text(jwk, "e");
        if (modulus == null || exponent == null || modulus.isEmpty() || exponent.isEmpty()) {
            throw new JwtException(messages.invalidToken());
        }
        try {
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(exponent));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (Exception ex) {
            throw new JwtException(messages.invalidToken());
        }
    }

    /** Fetch + cache a provider JWKS document (Python {@code _certs()}). */
    private static List<JsonNode> fetchKeys(String jwksUrl, String userAgent, Messages messages) throws JwtException {
        CacheEntry cached = KEY_CACHE.get(jwksUrl);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.keys();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JwtException(messages.keysUnreachable());
        } catch (Exception ex) {
            throw new JwtException(messages.keysUnreachable());
        }
        if (response.statusCode() >= 400) {
            throw new JwtException(messages.keysUnreachable());
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw new JwtException(messages.keysUnreachable());
        }
        if (payload == null || !payload.isObject()) {
            throw new JwtException(messages.invalidToken());
        }
        int maxAge = 3600;
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        for (String part : cacheControl.split(",")) {
            String item = part.trim();
            if (item.startsWith("max-age=")) {
                try {
                    maxAge = Integer.parseInt(item.substring("max-age=".length()).trim());
                } catch (NumberFormatException ex) {
                    throw new JwtException(messages.keysUnreachable());
                }
            }
        }
        JsonNode keysNode = payload.get("keys");
        List<JsonNode> keys = new ArrayList<>();
        if (keysNode != null && keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            throw new JwtException(messages.noSigningKeys());
        }
        KEY_CACHE.put(jwksUrl, new CacheEntry(Instant.now().plusSeconds(Math.max(60, maxAge)), List.copyOf(keys)));
        return keys;
    }

    private static JsonNode decodeSegment(String segment) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(segment);
            return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
