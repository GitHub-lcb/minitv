package us.minitv.paccount;

import org.springframework.stereotype.Service;
import us.minitv.account.row.UserRow;
import us.minitv.common.Times;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/me, PATCH /v1/me, GET /v1/me/ledger and POST /v1/me/reminder,
 * ported from public_flow.py and billing/service.py (active_membership).
 */
@Service
public class PAccountMeService {

    private final PAccountUserMapper users;
    private final PAccountWalletMapper wallets;
    private final PAccountLedgerMapper ledger;
    private final PAccountMembershipMapper memberships;

    public PAccountMeService(PAccountUserMapper users, PAccountWalletMapper wallets,
                             PAccountLedgerMapper ledger, PAccountMembershipMapper memberships) {
        this.users = users;
        this.wallets = wallets;
        this.ledger = ledger;
        this.memberships = memberships;
    }

    /** GET /v1/me: profile + coin balance + active membership window. */
    public Map<String, Object> me(UserRow user) {
        WalletAccountRow wallet = wallets.findById(user.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("public_id", user.getPublicId());
        payload.put("email", user.getEmail());
        payload.put("display_name", user.getDisplayName());
        payload.put("avatar_url", user.getAvatarUrl());
        payload.put("gender", user.getGender());
        payload.put("bio", user.getBio());
        payload.put("birthday", user.getBirthday());
        payload.put("region", user.getRegion());
        payload.put("locale", user.getLocale());
        payload.put("following_count", user.getFollowingCount());
        payload.put("follower_count", user.getFollowerCount());
        payload.put("received_likes", user.getReceivedLikes());
        payload.put("coins", wallet == null ? 0 : wallet.getBalance());
        payload.put("reminder_opt_in", user.getReminderOptIn());
        payload.put("membership", membership(user.getId()));
        return payload;
    }

    /**
     * PATCH /v1/me: Pydantic's {@code model_dump(exclude_none=True)} — only keys
     * the client actually sent (non-null) are written.
     *
     * <p>SQLAlchemy only flushed attributes whose value really changed, so the
     * same guard is applied here: a PATCH that repeats the stored values writes
     * nothing (and leaves {@code updated_at} untouched).</p>
     */
    public Map<String, Object> updateProfile(UserRow user, PAccountProfileIn body) {
        String displayName = changed(body.displayName(), user.getDisplayName());
        String avatarUrl = changed(body.avatarUrl(), user.getAvatarUrl());
        String gender = changed(body.gender(), user.getGender());
        String bio = changed(body.bio(), user.getBio());
        String region = changed(body.region(), user.getRegion());
        String locale = changed(body.locale(), user.getLocale());
        LocalDate birthday = body.birthday() == null ? null : LocalDate.parse(body.birthday());
        if (birthday != null && birthday.equals(user.getBirthday())) {
            birthday = null;
        }
        boolean any = displayName != null || avatarUrl != null || gender != null || bio != null
                || birthday != null || region != null || locale != null;
        if (any) {
            users.updateProfile(user.getId(), displayName, avatarUrl, gender, bio, birthday, region, locale);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        return payload;
    }

    /** Null when the field was not sent or already holds that value. */
    private static String changed(String sent, String current) {
        if (sent == null || sent.equals(current)) {
            return null;
        }
        return sent;
    }

    /** GET /v1/me/ledger: every coin change, newest first, no pagination. */
    public Map<String, Object> ledger(UserRow user) {
        List<CoinLedgerRow> rows = ledger.listByUser(user.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (CoinLedgerRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("amount", row.getAmount());
            item.put("balance_after", row.getBalanceAfter());
            item.put("biz_type", row.getBizType());
            item.put("created_at", row.getCreatedAt());
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        return payload;
    }

    /** POST /v1/me/reminder. */
    public Map<String, Object> setReminder(UserRow user, boolean enabled) {
        // SQLAlchemy only flushed a real change; keep updated_at untouched on a no-op.
        if (!Boolean.valueOf(enabled).equals(user.getReminderOptIn())) {
            users.updateReminderOptIn(user.getId(), enabled);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", enabled);
        return payload;
    }

    /** billing/service.py active_membership: whichever access window ends last. */
    private Map<String, Object> membership(long userId) {
        PAccountMembershipRow row = memberships.findActiveMembership(userId);
        LocalDateTime now = Times.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (row == null) {
            payload.put("active", false);
            payload.put("plan_code", null);
            payload.put("until", null);
            payload.put("canceling", false);
            payload.put("billing_mode", null);
            payload.put("subscription_until", null);
            payload.put("pass_until", null);
            return payload;
        }
        LocalDateTime subscriptionUntil = row.getCurrentPeriodEnd();
        LocalDateTime passUntil = row.getPassEnd();
        List<LocalDateTime> live = new ArrayList<>();
        if (subscriptionUntil != null && subscriptionUntil.isAfter(now)) {
            live.add(subscriptionUntil);
        }
        if (passUntil != null && passUntil.isAfter(now)) {
            live.add(passUntil);
        }
        if (live.isEmpty()) {
            payload.put("active", false);
            payload.put("plan_code", null);
            payload.put("until", null);
            payload.put("canceling", false);
            payload.put("billing_mode", null);
            payload.put("subscription_until", subscriptionUntil);
            payload.put("pass_until", passUntil);
            return payload;
        }
        LocalDateTime until = Collections.max(live);
        boolean oneTime = passUntil != null && passUntil.equals(until)
                && (subscriptionUntil == null || passUntil.isAfter(subscriptionUntil));
        String mode = oneTime ? "one_time" : "subscription";
        boolean canceling = "subscription".equals(mode) && row.getCanceledAt() != null
                && subscriptionUntil != null && subscriptionUntil.isAfter(now);
        payload.put("active", true);
        payload.put("plan_code", row.getPlanCode());
        payload.put("until", until);
        payload.put("canceling", canceling);
        payload.put("billing_mode", mode);
        payload.put("subscription_until", subscriptionUntil);
        payload.put("pass_until", passUntil);
        return payload;
    }
}
