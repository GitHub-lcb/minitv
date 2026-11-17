package us.minitv.paccount;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.common.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public authentication endpoints, ported from modules/public_flow.py:
 *
 * <ul>
 *   <li>POST /v1/auth/email/code</li>
 *   <li>GET  /v1/auth/providers</li>
 *   <li>POST /v1/auth/email/authenticate</li>
 *   <li>POST /v1/auth/email/register</li>
 *   <li>POST /v1/auth/email/login</li>
 *   <li>POST /v1/auth/google</li>
 *   <li>POST /v1/auth/facebook</li>
 *   <li>POST /v1/auth/oauth/{provider}</li>
 * </ul>
 */
@RestController
public class PAccountAuthController {

    private final PAccountAuthService auth;

    public PAccountAuthController(PAccountAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/v1/auth/email/code")
    public Map<String, Object> emailCode(@Valid @RequestBody PAccountCodeIn body) {
        auth.issueCode(body.email(), body.purpose());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sent", true);
        return payload;
    }

    @GetMapping("/v1/auth/providers")
    public Map<String, Object> providers() {
        return auth.providers();
    }

    @PostMapping("/v1/auth/email/authenticate")
    public Map<String, Object> authenticate(@Valid @RequestBody PAccountLoginIn body) {
        return authBody(auth.authenticateWithCode(body.email(), body.code()), true);
    }

    @PostMapping("/v1/auth/email/register")
    public Map<String, Object> register(@Valid @RequestBody PAccountRegisterIn body) {
        if (body.password() != null) {
            throw ApiException.unprocessable("password is not accepted");
        }
        return authBody(auth.register(body.email(), body.code()), false);
    }

    @PostMapping("/v1/auth/email/login")
    public Map<String, Object> login(@Valid @RequestBody PAccountLoginIn body) {
        return authBody(auth.login(body.email(), body.code()), false);
    }

    @PostMapping("/v1/auth/google")
    public Map<String, Object> google(@Valid @RequestBody PAccountGoogleIn body) {
        return authBody(auth.loginWithGoogle(body.idToken()), true);
    }

    @PostMapping("/v1/auth/facebook")
    public Map<String, Object> facebook(@Valid @RequestBody PAccountFacebookIn body) {
        if (body.accessToken() == null && body.idToken() == null) {
            throw ApiException.unprocessable("access_token or id_token required");
        }
        return authBody(auth.loginWithFacebook(body.accessToken(), body.idToken()), true);
    }

    @PostMapping("/v1/auth/oauth/{provider}")
    public Map<String, Object> oauth(@PathVariable("provider") String provider) {
        // Python oauth_login() only returns when OAUTH_<PROVIDER>_ENABLED=1, and
        // the endpoint still answers 501 because no flow was ever implemented.
        auth.oauthLogin(provider);
        throw ApiException.of(501, "not configured");
    }

    private static Map<String, Object> authBody(PAccountAuthService.AuthResult result,
                                                boolean includeCreated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", result.token());
        payload.put("user_id", result.userId());
        payload.put("public_id", result.publicId());
        if (includeCreated) {
            payload.put("created", result.created());
        }
        return payload;
    }
}
