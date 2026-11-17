package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.util.Map;

/**
 * Self-service API keys. Reads accept a key (scope {@code read}); every mutation
 * and the full listing stay session-only so a key can never mint another.
 */
@RestController
public class PartnerApiKeyController {

    private final PartnerApiKeyService keys;

    public PartnerApiKeyController(PartnerApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/partner/v1/api-keys")
    public Map<String, Object> listKeys(@CurrentPartner PartnerContext partner) {
        return keys.list(partner.user());
    }

    @GetMapping("/partner/v1/auth/keys")
    public Map<String, Object> listOwnKeys(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner) {
        return keys.listOwn(partner.user());
    }

    /** Metadata of the calling key only — what the CLI needs for {@code whoami}. */
    @GetMapping("/partner/v1/auth/key-info")
    public Map<String, Object> currentKeyInfo(@CurrentPartner PartnerContext partner) {
        return keys.keyInfo(partner.token());
    }

    @PostMapping("/partner/v1/api-keys")
    public ResponseEntity<Map<String, Object>> createKey(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(keys.create(partner.user(), body));
    }

    @PatchMapping("/partner/v1/api-keys/{key_id}")
    public Map<String, Object> updateKey(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner,
            @PathVariable("key_id") long keyId,
            @RequestBody(required = false) JsonNode body) {
        return keys.update(partner.user(), keyId, body);
    }

    @PostMapping("/partner/v1/api-keys/{key_id}/rotate")
    public ResponseEntity<Map<String, Object>> rotateKey(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner,
            @PathVariable("key_id") long keyId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(keys.rotate(partner.user(), keyId));
    }

    @PostMapping("/partner/v1/api-keys/{key_id}/revoke")
    public Map<String, Object> revokeKey(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner,
            @PathVariable("key_id") long keyId,
            @RequestBody(required = false) JsonNode body) {
        return keys.revoke(partner.user(), keyId, body);
    }
}
