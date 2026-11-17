package us.minitv.paccount;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import us.minitv.common.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Google Sign-In ID token verification, ported from account/google.py.
 *
 * <p>Only a signature that matches Google's published keys and an audience
 * that matches this app is trusted. An unverified email is dropped so it
 * cannot be used to merge accounts.</p>
 */
@Service
public class PAccountGoogleAuth {

    private static final String GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> GOOGLE_ISSUERS =
            List.of("accounts.google.com", "https://accounts.google.com");
    private static final PAccountJwtSupport.Messages MESSAGES = new PAccountJwtSupport.Messages(
            "google keys are unreachable",
            "google published no signing keys",
            "google signing key not found",
            "invalid google token");

    @Value("${GOOGLE_CLIENT_ID:}")
    private String googleClientId;

    @Value("${GOOGLE_CLIENT_IDS:}")
    private String googleClientIds;

    /** Claims used for account linking (account/google.py GoogleIdentity). */
    public record Identity(String subject, String email, boolean emailVerified, String name, String picture) {
    }

    /** Verify a Google ID token; failures become 401 with the Python text. */
    public Identity verify(String token) {
        List<String> audiences = audiences();
        JsonNode claims;
        try {
            claims = PAccountJwtSupport.verify(token, audiences, GOOGLE_ISSUERS,
                    GOOGLE_CERTS_URL, "minitv-google", MESSAGES);
        } catch (PAccountJwtSupport.JwtException ex) {
            throw ApiException.of(401, ex.getMessage());
        }
        JsonNode subjectNode = claims.get("sub");
        if (subjectNode == null || !subjectNode.isTextual() || subjectNode.asText().isEmpty()) {
            throw ApiException.of(401, "google account has no subject");
        }
        boolean verified = claims.has("email_verified")
                && claims.get("email_verified").isBoolean()
                && claims.get("email_verified").booleanValue();
        String email = null;
        JsonNode rawEmail = claims.get("email");
        if (verified && rawEmail != null && rawEmail.isTextual()) {
            String cleaned = rawEmail.asText().strip().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) {
                email = cleaned;
            }
        }
        JsonNode nameNode = claims.get("name");
        String name = nameNode != null && nameNode.isTextual()
                ? PAccountText.truncate(nameNode.asText(), 64) : "";
        JsonNode pictureNode = claims.get("picture");
        String picture = pictureNode != null && pictureNode.isTextual()
                ? PAccountText.truncate(pictureNode.asText(), 500) : "";
        return new Identity(subjectNode.asText(), email, verified, name, picture);
    }

    /** Every client id allowed to mint a token for this backend. */
    private List<String> audiences() {
        List<String> audiences = new ArrayList<>();
        for (String raw : List.of(googleClientId == null ? "" : googleClientId,
                googleClientIds == null ? "" : googleClientIds)) {
            for (String item : raw.split(",", -1)) {
                String clientId = item.strip();
                if (!clientId.isEmpty() && !audiences.contains(clientId)) {
                    audiences.add(clientId);
                }
            }
        }
        if (audiences.isEmpty()) {
            throw ApiException.of(401, "google is not configured");
        }
        return audiences;
    }
}
