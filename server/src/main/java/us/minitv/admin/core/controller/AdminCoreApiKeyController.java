package us.minitv.admin.core.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreApiKeyService;
import us.minitv.common.ApiException;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /admin/v1/api-keys - long-lived credentials bound to a staff account. */
@RestController
public class AdminCoreApiKeyController {

    public record RevokeIn(String reason) {
    }

    private final AdminCoreApiKeyService keys;

    public AdminCoreApiKeyController(AdminCoreApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/admin/v1/api-keys")
    public Map<String, Object> list(@CurrentStaff StaffContext ctx,
                                    @RequestParam(value = "user_id", required = false) Long userId,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return keys.listKeys(userId, status, q, page, pageSize);
    }

    @GetMapping("/admin/v1/api-keys/{key_id}/events")
    public Map<String, Object> events(@CurrentStaff StaffContext ctx,
                                      @PathVariable("key_id") long keyId,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        if (limit < 1) {
            throw ApiException.unprocessable("limit: Input should be greater than or equal to 1");
        }
        if (limit > 200) {
            throw ApiException.unprocessable("limit: Input should be less than or equal to 200");
        }
        return keys.listKeyEvents(keyId, limit);
    }

    /** Self-service view: a staff member can audit their own credentials. */
    @GetMapping("/admin/v1/auth/keys")
    public Map<String, Object> ownKeys(@CurrentStaff StaffContext ctx) {
        return keys.listOwnKeys(ctx.user());
    }

    @PostMapping("/admin/v1/api-keys")
    public ResponseEntity<Map<String, Object>> create(@CurrentStaff StaffContext ctx,
                                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        long userId = requiredUserId(body);
        String name = requiredName(body);
        List<String> scopes = optionalScopes(body);
        Integer expiresInDays = expiresInDays(body, AdminCoreApiKeyService.DEFAULT_TTL_DAYS);
        Map<String, Object> created = keys.createKey(ctx.user(), userId, name, scopes, expiresInDays);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/admin/v1/api-keys/{key_id}")
    public Map<String, Object> update(@CurrentStaff StaffContext ctx,
                                      @PathVariable("key_id") long keyId,
                                      @RequestBody Map<String, Object> body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return keys.updateKey(ctx.user(), keyId, body);
    }

    @PostMapping("/admin/v1/api-keys/{key_id}/revoke")
    public Map<String, Object> revoke(@CurrentStaff StaffContext ctx,
                                      @PathVariable("key_id") long keyId,
                                      @RequestBody(required = false) RevokeIn body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        return keys.revokeKey(ctx.user(), keyId, body == null ? null : body.reason());
    }

    @PostMapping("/admin/v1/api-keys/{key_id}/rotate")
    public ResponseEntity<Map<String, Object>> rotate(@CurrentStaff StaffContext ctx,
                                                      @PathVariable("key_id") long keyId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        // Omitted -> 90 days (Python's default); explicit null -> never expires.
        Integer expiresInDays = expiresInDays(body, AdminCoreApiKeyService.DEFAULT_TTL_DAYS);
        Map<String, Object> rotated = keys.rotateKey(ctx.user(), keyId, expiresInDays);
        return ResponseEntity.status(HttpStatus.CREATED).body(rotated);
    }

    @DeleteMapping("/admin/v1/api-keys/{key_id}")
    public ResponseEntity<Void> delete(@CurrentStaff StaffContext ctx,
                                       @PathVariable("key_id") long keyId) {
        AdminCoreSupport.requireAdminRole(ctx.user());
        keys.deleteKey(ctx.user(), keyId);
        return ResponseEntity.noContent().build();
    }

    // ---- body helpers (presence matters for expires_in_days / scopes) ----

    private static long requiredUserId(Map<String, Object> body) {
        Object value = body == null ? null : body.get("user_id");
        if (value == null) {
            throw ApiException.unprocessable("user_id: Field required");
        }
        if (!(value instanceof Number number)) {
            throw ApiException.unprocessable("user_id: Input should be a valid integer");
        }
        return number.longValue();
    }

    private static String requiredName(Map<String, Object> body) {
        Object value = body == null ? null : body.get("name");
        if (value == null) {
            throw ApiException.unprocessable("name: Field required");
        }
        if (!(value instanceof String text)) {
            throw ApiException.unprocessable("name: Input should be a valid string");
        }
        if (text.length() < 1) {
            throw ApiException.unprocessable("name: String should have at least 1 character");
        }
        if (text.length() > 64) {
            throw ApiException.unprocessable("name: String should have at most 64 characters");
        }
        return text;
    }

    private static List<String> optionalScopes(Map<String, Object> body) {
        Object value = body == null ? null : body.get("scopes");
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw ApiException.unprocessable("scopes: Input should be a valid list");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) {
                out.add(text);
            } else {
                throw ApiException.unprocessable("scopes: Input should be a valid list of strings");
            }
        }
        return out;
    }

    /** Omitted -> default 90; explicit null -> never expires. */
    private static Integer expiresInDays(Map<String, Object> body, int fallback) {
        if (body == null || !body.containsKey("expires_in_days")) {
            return fallback;
        }
        Object value = body.get("expires_in_days");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw ApiException.unprocessable("expires_in_days: Input should be a valid integer");
        }
        return number.intValue();
    }
}
