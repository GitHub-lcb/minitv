package us.minitv.security;

import org.junit.jupiter.api.Test;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminApiKeyRow;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the security primitives shared by every ported module. */
class SecurityPrimitivesTest {

    /** Produced by Python's hashlib.pbkdf2_hmac with the same salt/iterations. */
    private static final String PYTHON_HASH =
            "pbkdf2_sha256$120000$00112233445566778899aabbccddeeff$"
                    + "864c0faeb38d350e6c7259f4d0502fd54c63301a2f04da10943f53e1bef1be45";

    @Test
    void passwordHashesAreInterchangeableWithPython() {
        assertTrue(PasswordHasher.verify("secret-123", PYTHON_HASH));
        assertFalse(PasswordHasher.verify("wrong", PYTHON_HASH));

        String ours = PasswordHasher.hash("admin-pass-1");
        assertTrue(ours.startsWith("pbkdf2_sha256$120000$"));
        assertEquals(4, ours.split("\\$").length);
        assertTrue(PasswordHasher.verify("admin-pass-1", ours));
    }

    @Test
    void apiKeyScopePolicyMatchesFastApiRouting() {
        assertEquals("read", ApiKeyService.scopeForRequest("GET", "/admin/v1/dramas"));
        assertEquals("write", ApiKeyService.scopeForRequest("POST", "/admin/v1/dramas"));
        assertEquals("delete", ApiKeyService.scopeForRequest("POST", "/admin/v1/dramas/5/delete"));
        assertEquals("delete", ApiKeyService.scopeForRequest("DELETE", "/admin/v1/episodes/3"));
        assertEquals("staff", ApiKeyService.scopeForRequest("POST", "/admin/v1/api-keys"));
        assertEquals("staff", ApiKeyService.scopeForRequest("POST", "/admin/v1/auth/password"));
        assertEquals("billing", ApiKeyService.scopeForRequest("POST", "/admin/v1/plans/monthly/price"));
        assertEquals("upload", ApiKeyService.scopeForRequest("POST", "/partner/v1/episodes/9/upload-session"));
        assertEquals("read", ApiKeyService.scopeForRequest("GET", "/partner/v1/api-keys"));
        assertEquals("staff", ApiKeyService.scopeForRequest("POST", "/partner/v1/api-keys"));
    }

    @Test
    void partnerKeysCannotGrantStaffScopes() {
        AdminApiKeyRow key = new AdminApiKeyRow();
        key.setScopes("[]");
        Set<String> granted = ApiKeyService.grantedScopes(key, "partner");
        assertEquals(Set.of("read", "write", "upload"), granted);

        key.setScopes("[\"read\", \"billing\"]");
        assertEquals(Set.of("read"), ApiKeyService.grantedScopes(key, "partner"));
    }

    @Test
    void scopesAreNormalisedAndValidated() {
        assertEquals(List.of("read", "write"), ApiKeyService.normaliseScopes(null, null));
        assertEquals(List.of("upload", "read"), ApiKeyService.normaliseScopes(List.of("upload", "read"), null));
        org.junit.jupiter.api.Assertions.assertThrows(us.minitv.common.ApiException.class,
                () -> ApiKeyService.normaliseScopes(List.of(), null));
        org.junit.jupiter.api.Assertions.assertThrows(us.minitv.common.ApiException.class,
                () -> ApiKeyService.normaliseScopes(List.of("root"), null));
    }

    @Test
    void tokensMatchPythonUrlSafeShape() {
        assertEquals(43, Tokens.urlSafe(32).length());
        assertEquals(8, Tokens.hex(4).length());
        assertFalse(Tokens.urlSafe(32).contains("="));
    }

    @Test
    void businessDayUsesLosAngelesNotUtc() {
        // 2026-11-17T04:00Z is still 2026-11-16 in Los Angeles (PST, UTC-8).
        assertEquals(LocalDate.of(2026, 11, 16), Times.businessDay(LocalDateTime.of(2026, 11, 17, 4, 0)));
        assertEquals(LocalDate.of(2026, 11, 17), Times.businessDay(LocalDateTime.of(2026, 11, 17, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 11, 17, 8, 0), Times.dayStartUtc(LocalDate.of(2026, 11, 17)));
    }
}
