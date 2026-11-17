package us.minitv.paccount;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import us.minitv.account.row.ThirdPartyIdentitieRow;
import us.minitv.account.row.UserRow;
import us.minitv.account.row.VerificationCodeRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.security.PasswordHasher;
import us.minitv.security.RedisStore;
import us.minitv.security.SessionService;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Email codes, registration, code sign-in and social account linking, ported
 * from account/service.py.
 *
 * <p>Verification codes are stored as SHA-256 hashes; the plaintext is never
 * logged or returned by the API. The same {@code _consume_code} rules (newest
 * row, 10-minute expiry, five attempts, single use) apply to every flow so
 * register and login cannot drift.</p>
 */
@Service
public class PAccountAuthService {

    private static final List<String> CODE_PURPOSES = List.of("login", "register", "bind");
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final long PUBLIC_ID_BASE = 1_000_000_000L;
    private static final long PUBLIC_ID_SPAN = 9_000_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PAccountUserMapper users;
    private final PAccountVerificationCodeMapper codes;
    private final PAccountThirdPartyIdentityMapper identities;
    private final PAccountWalletMapper wallets;
    private final PAccountRewardService rewardService;
    private final SessionService sessions;
    private final RedisStore redis;
    private final PAccountMailService mail;
    private final PAccountGoogleAuth google;
    private final PAccountFacebookAuth facebook;
    private final Environment environment;
    private final TransactionTemplate tx;

    @Value("${FACEBOOK_LINK_BY_EMAIL:1}")
    private String facebookLinkByEmail;

    public PAccountAuthService(PAccountUserMapper users,
                               PAccountVerificationCodeMapper codes,
                               PAccountThirdPartyIdentityMapper identities,
                               PAccountWalletMapper wallets,
                               PAccountRewardService rewardService,
                               SessionService sessions,
                               RedisStore redis,
                               PAccountMailService mail,
                               PAccountGoogleAuth google,
                               PAccountFacebookAuth facebook,
                               Environment environment,
                               PlatformTransactionManager transactionManager) {
        this.users = users;
        this.codes = codes;
        this.identities = identities;
        this.wallets = wallets;
        this.rewardService = rewardService;
        this.sessions = sessions;
        this.redis = redis;
        this.mail = mail;
        this.google = google;
        this.facebook = facebook;
        this.environment = environment;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Sign-in / sign-up outcome shared by every auth endpoint. */
    public record AuthResult(String token, long userId, String publicId, Boolean created) {
    }

    // ------------------------------------------------------------------ codes

    /** POST /v1/auth/email/code: rate limited per purpose+email, then mailed. */
    public void issueCode(String email, String purpose) {
        String normalizedPurpose = normalizePurpose(purpose);
        String normalizedEmail = normalizeEmail(email);
        String rateKey = "code_rate:" + normalizedPurpose + ":" + normalizedEmail;
        long count = redis.increment(rateKey);
        if (count == 1) {
            redis.expire(rateKey, Duration.ofHours(1));
        }
        if (count > 5) {
            throw ApiException.of(429, "too many codes");
        }
        String code = generateCode();
        VerificationCodeRow row = new VerificationCodeRow();
        row.setEmail(normalizedEmail);
        row.setPurpose(normalizedPurpose);
        row.setCodeHash(PasswordHasher.sha256Hex(code));
        row.setAttempts(0);
        row.setExpiresAt(Times.now().plusMinutes(10));
        codes.insert(row);
        try {
            mail.sendVerificationCode(normalizedEmail, code, normalizedPurpose);
        } catch (PAccountMailService.MailException ex) {
            // Python deletes the orphan code and answers 503.
            codes.deleteById(row.getId());
            throw ApiException.serviceUnavailable("email delivery failed");
        }
    }

    /**
     * Verify the newest code for this email/purpose and burn it.
     *
     * <p>A wrong code increments the attempt counter before raising; that write
     * commits on its own so the limit survives the failed request, exactly like
     * Python committing before raising.</p>
     */
    private VerificationCodeRow consumeCode(String email, String purpose, String code) {
        VerificationCodeRow row = codes.findLatest(email, purpose);
        if (row == null || row.getExpiresAt().isBefore(Times.now())) {
            throw ApiException.of(400, "code expired");
        }
        boolean matches = PasswordHasher.constantTimeEquals(row.getCodeHash(),
                PasswordHasher.sha256Hex(code == null ? "" : code));
        if (row.getAttempts() >= MAX_CODE_ATTEMPTS || !matches) {
            codes.incrementAttempts(row.getId());
            throw ApiException.of(400, "invalid code");
        }
        return row;
    }

    private static String normalizePurpose(String purpose) {
        String value = (purpose == null ? "" : purpose).strip().toLowerCase(Locale.ROOT);
        if (!CODE_PURPOSES.contains(value)) {
            throw ApiException.unprocessable("invalid purpose");
        }
        return value;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    // ------------------------------------------------------------ providers

    /** GET /v1/auth/providers: what this deployment can actually sign in with. */
    public Map<String, Object> providers() {
        String clientId = firstGoogleClientId();
        String facebookAppId = environment.getProperty("FACEBOOK_APP_ID", "").strip();
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("enabled", true);
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("enabled", clientId != null);
        google.put("client_id", clientId);
        Map<String, Object> facebookProvider = new LinkedHashMap<>();
        facebookProvider.put("enabled", !facebookAppId.isEmpty());
        facebookProvider.put("app_id", facebookAppId.isEmpty() ? null : facebookAppId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", email);
        payload.put("google", google);
        payload.put("facebook", facebookProvider);
        return payload;
    }

    private String firstGoogleClientId() {
        for (String name : List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_IDS")) {
            for (String item : environment.getProperty(name, "").split(",", -1)) {
                String clientId = item.strip();
                if (!clientId.isEmpty()) {
                    return clientId;
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- flows

    /** POST /v1/auth/email/authenticate: the same code signs in, or signs up. */
    public AuthResult authenticateWithCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        VerificationCodeRow row = consumeCode(normalizedEmail, "login", code);
        return tx.execute(status -> {
            codes.deleteById(row.getId());
            UserRow user = users.findByEmail(normalizedEmail);
            boolean created = false;
            if (user == null) {
                user = newUser(normalizedEmail, splitLocalPart(normalizedEmail), "");
                created = true;
            }
            String token = sessions.createUserSession(user.getId());
            return new AuthResult(token, user.getId(), user.getPublicId(), created);
        });
    }

    /** POST /v1/auth/email/register. */
    public AuthResult register(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        VerificationCodeRow row = consumeCode(normalizedEmail, "register", code);
        return tx.execute(status -> {
            codes.deleteById(row.getId());
            if (users.findByEmail(normalizedEmail) != null) {
                throw ApiException.conflict("email already registered");
            }
            UserRow user = newUser(normalizedEmail, splitLocalPart(normalizedEmail), "");
            String token = sessions.createUserSession(user.getId());
            return new AuthResult(token, user.getId(), user.getPublicId(), null);
        });
    }

    /** POST /v1/auth/email/login: a missing account is an error, not a sign-up. */
    public AuthResult login(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        VerificationCodeRow row = consumeCode(normalizedEmail, "login", code);
        return tx.execute(status -> {
            codes.deleteById(row.getId());
            UserRow user = users.findByEmail(normalizedEmail);
            if (user == null) {
                throw ApiException.notFound("email not registered");
            }
            String token = sessions.createUserSession(user.getId());
            return new AuthResult(token, user.getId(), user.getPublicId(), null);
        });
    }

    /** POST /v1/auth/google. */
    public AuthResult loginWithGoogle(String idToken) {
        PAccountGoogleAuth.Identity identity = google.verify(idToken);
        return linkIdentity("google", identity.subject(), identity.email(),
                identity.name(), identity.picture(), true);
    }

    /** POST /v1/auth/facebook. */
    public AuthResult loginWithFacebook(String accessToken, String idToken) {
        PAccountFacebookAuth.Identity identity = facebook.verify(accessToken, idToken);
        return linkIdentity("facebook", identity.subject(), identity.email(),
                identity.name(), identity.picture(), facebookLinksByEmail());
    }

    /** POST /v1/auth/oauth/{provider}: only unsupported providers arrive here. */
    public void oauthLogin(String provider) {
        if ("google".equals(provider) || "facebook".equals(provider)) {
            throw ApiException.of(400, "use /v1/auth/" + provider);
        }
        String flag = environment.getProperty(
                "OAUTH_" + provider.toUpperCase(Locale.ROOT) + "_ENABLED", "");
        if (!"1".equals(flag)) {
            throw ApiException.of(501, provider + " is not configured");
        }
    }

    private boolean facebookLinksByEmail() {
        String flag = facebookLinkByEmail == null ? "1" : facebookLinkByEmail;
        return !flag.strip().equals("0");
    }

    /**
     * Attach a provider subject to a user, creating the user if needed
     * (account/service.py {@code _link_identity}).
     */
    private AuthResult linkIdentity(String provider, String subject, String identityEmail,
                                    String name, String picture, boolean mergeByEmail) {
        return tx.execute(status -> {
            String email = identityEmail;
            ThirdPartyIdentitieRow linked = identities.findByProviderAndSubject(provider, subject);
            boolean created = false;
            UserRow user;
            if (linked != null) {
                user = users.findById(linked.getUserId());
                if (user == null) {
                    throw ApiException.of(401, provider + " account is not linked");
                }
                if (email != null && user.getEmail() == null && users.findByEmail(email) == null) {
                    users.updateEmail(user.getId(), email);
                    user.setEmail(email);
                }
            } else {
                user = email == null ? null : users.findByEmail(email);
                if (user != null && !mergeByEmail) {
                    // The address already belongs to someone who signed up
                    // another way; keep the two accounts apart.
                    user = null;
                    email = null;
                }
                if (user == null) {
                    user = newUser(email, name, picture);
                    created = true;
                }
                identities.insert(user.getId(), provider, subject);
            }
            if (name != null && !name.isEmpty()
                    && (user.getDisplayName() == null || user.getDisplayName().isEmpty())) {
                users.updateDisplayName(user.getId(), name);
                user.setDisplayName(name);
            }
            if (picture != null && !picture.isEmpty()
                    && (user.getAvatarUrl() == null || user.getAvatarUrl().isEmpty())) {
                users.updateAvatarUrl(user.getId(), picture);
                user.setAvatarUrl(picture);
            }
            String token = sessions.createUserSession(user.getId());
            return new AuthResult(token, user.getId(), user.getPublicId(), created);
        });
    }

    /** Python {@code _new_user}: profile row, wallet and the registration reward. */
    private UserRow newUser(String email, String displayName, String avatarUrl) {
        UserRow user = new UserRow();
        user.setEmail(email);
        String name = displayName == null ? "" : displayName;
        if (name.isEmpty()) {
            name = email != null && !email.isEmpty() ? splitLocalPart(email) : "MiniTV";
        }
        user.setDisplayName(PAccountText.truncate(name, 64));
        user.setAvatarUrl(avatarUrl == null ? "" : PAccountText.truncate(avatarUrl, 500));
        user.setGender("unspecified");
        user.setBio("");
        user.setBirthday(null);
        user.setRegion("");
        user.setLocale("en");
        user.setPublicId(String.valueOf(PUBLIC_ID_BASE + RANDOM.nextLong(PUBLIC_ID_SPAN)));
        user.setFollowingCount(0);
        user.setFollowerCount(0);
        user.setReceivedLikes(0);
        user.setReminderOptIn(false);
        users.insert(user);
        wallets.insert(user.getId(), 0);
        rewardService.credit(user.getId(),
                rewardService.activeAmount("register", Times.laToday()),
                "register_reward", "register:" + user.getId());
        return user;
    }

    private static String splitLocalPart(String email) {
        return email == null ? "" : email.split("@", -1)[0];
    }
}
