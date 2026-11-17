package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.util.Map;

/**
 * Supplier profile and credentials ({@code /partner/v1/me}, password change,
 * logout). Credential management must never be reachable with an API key, so
 * those routes resolve {@code @CurrentPartner(apiKeyAllowed = false)}.
 */
@RestController
public class PartnerAuthController {

    private final PartnerAuthService auth;

    public PartnerAuthController(PartnerAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/partner/v1/me")
    public Map<String, Object> me(@CurrentPartner PartnerContext partner) {
        return auth.me(partner);
    }

    @PostMapping("/partner/v1/auth/password")
    public Map<String, Object> changePassword(
            @CurrentPartner(apiKeyAllowed = false) PartnerContext partner,
            @RequestBody(required = false) JsonNode body) {
        return auth.changePassword(partner.user(), body);
    }

    @PostMapping("/partner/v1/auth/logout")
    public Map<String, Object> logout(@CurrentPartner(apiKeyAllowed = false) PartnerContext partner) {
        return auth.logout(partner.token());
    }
}
