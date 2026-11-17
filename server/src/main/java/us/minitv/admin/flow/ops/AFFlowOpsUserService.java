package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.ops.mapper.AdminUserMapper;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Admin-side end-user listing, profile edits, coin adjustments and membership. */
@Service
public class AFFlowOpsUserService {

    private static final Set<String> GENDERS = Set.of("unspecified", "female", "male", "other");

    private final AFFlowOpsUserMapper users;
    private final AFFlowOpsAuditMapper audit;
    private final AFFlowOpsBillingService billing;
    private final AdminUserMapper adminUsers;

    public AFFlowOpsUserService(AFFlowOpsUserMapper users, AFFlowOpsAuditMapper audit,
                                AFFlowOpsBillingService billing, AdminUserMapper adminUsers) {
        this.users = users;
        this.audit = audit;
        this.billing = billing;
        this.adminUsers = adminUsers;
    }

    public Map<String, Object> listUsers(String q) {
        String needle = null;
        String like = null;
        if (q != null) {
            String stripped = q.strip();
            if (!stripped.isEmpty()) {
                needle = stripped;
                like = "%" + stripped + "%";
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserRow row : users.searchUsers(needle, like)) {
            WalletAccountRow wallet = users.findWallet(row.getId());
            Map<String, Object> membership = billing.activeMembership(row.getId());
            items.add(AFFlowOpsSupport.map(
                    "id", row.getId(),
                    "public_id", row.getPublicId(),
                    "display_name", row.getDisplayName(),
                    "email", row.getEmail(),
                    "avatar_url", row.getAvatarUrl(),
                    "gender", row.getGender(),
                    "bio", row.getBio(),
                    "region", row.getRegion(),
                    "locale", row.getLocale(),
                    "coins", wallet == null ? 0 : wallet.getBalance(),
                    "member", membership.get("active"),
                    "member_until", membership.get("until"),
                    "canceling", membership.get("canceling"),
                    "billing_mode", membership.get("billing_mode"),
                    "created_at", row.getCreatedAt()));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    @Transactional
    public Map<String, Object> updateUser(long userId, Map<String, Object> body, long actorId) {
        UserRow row = users.findUser(userId);
        if (row == null) {
            throw ApiException.notFound("user not found");
        }
        Map<String, Object> data = body == null ? Map.of() : body;
        Map<String, Object> before = new LinkedHashMap<>();
        if (data.containsKey("display_name")) {
            String name = data.get("display_name") == null ? "" : String.valueOf(data.get("display_name")).strip();
            if (name.isEmpty()) {
                throw ApiException.unprocessable("昵称不能为空");
            }
            requireMaxLength(name, 64);
            row.setDisplayName(name);
            before.put("display_name", name);
        }
        if (data.containsKey("email")) {
            String raw = data.get("email") == null ? "" : String.valueOf(data.get("email")).strip().toLowerCase();
            String email = raw.isEmpty() ? null : raw;
            requireMaxLength(raw, 320);
            if (email != null && (!email.contains("@") || email.startsWith("@") || email.endsWith("@"))) {
                throw ApiException.unprocessable("邮箱格式不正确");
            }
            if (email != null && users.findOtherUserIdByEmail(email, userId) != null) {
                throw new ApiException(409, "该邮箱已被其他用户绑定");
            }
            row.setEmail(email);
            before.put("email", email);
        }
        if (data.containsKey("gender")) {
            String raw = data.get("gender") == null ? "" : String.valueOf(data.get("gender")).strip();
            requireMaxLength(raw, 16);
            String gender = raw.isEmpty() ? "unspecified" : raw;
            if (!GENDERS.contains(gender)) {
                throw ApiException.unprocessable("性别取值不合法");
            }
            row.setGender(gender);
            before.put("gender", gender);
        }
        if (data.containsKey("avatar_url")) {
            String value = data.get("avatar_url") == null ? "" : String.valueOf(data.get("avatar_url")).strip();
            requireMaxLength(value, 500);
            row.setAvatarUrl(value);
            before.put("avatar_url", value);
        }
        if (data.containsKey("bio")) {
            String value = data.get("bio") == null ? "" : String.valueOf(data.get("bio")).strip();
            requireMaxLength(value, 500);
            row.setBio(value);
            before.put("bio", value);
        }
        if (data.containsKey("region")) {
            String value = data.get("region") == null ? "" : String.valueOf(data.get("region")).strip();
            requireMaxLength(value, 8);
            row.setRegion(value);
            before.put("region", value);
        }
        if (data.containsKey("locale")) {
            String raw = data.get("locale") == null ? "" : String.valueOf(data.get("locale")).strip();
            requireMaxLength(raw, 8);
            String value = raw.isEmpty() ? "en" : raw;
            row.setLocale(value);
            before.put("locale", value);
        }
        if (before.isEmpty()) {
            return AFFlowOpsSupport.map("updated", false);
        }
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("user_update");
        log.setTargetType("user");
        log.setTargetId(userId);
        log.setPayload(Json.write(before));
        audit.insert(log);
        users.updateUser(row);

        Map<String, Object> userOut = AFFlowOpsSupport.map("id", row.getId(), "public_id", row.getPublicId());
        userOut.putAll(before);
        return AFFlowOpsSupport.map("updated", true, "user", userOut);
    }

    @Transactional
    public Map<String, Object> adjustCoins(long userId, AFFlowOpsBodies.AdjustIn body, long actorId) {
        if (users.findUser(userId) == null) {
            throw ApiException.notFound("user not found");
        }
        if (body == null || body.delta() == null) {
            throw ApiException.unprocessable("Field required");
        }
        if (body.delta() == 0) {
            throw ApiException.unprocessable("delta must be non-zero");
        }
        if (body.reason() == null || body.reason().isEmpty()) {
            throw ApiException.unprocessable("String should have at least 1 character");
        }
        int delta = body.delta();
        WalletAccountRow wallet = users.lockWallet(userId);
        int before;
        if (wallet == null) {
            users.insertWallet(userId, 0);
            before = 0;
        } else {
            before = wallet.getBalance() == null ? 0 : wallet.getBalance();
        }
        int after = before + delta;
        if (after < 0) {
            throw new ApiException(409, "balance cannot be negative");
        }
        users.updateWalletBalance(userId, after);
        double timestamp = System.currentTimeMillis() / 1000.0;
        CoinLedgerRow entry = new CoinLedgerRow();
        entry.setUserId(userId);
        entry.setAmount(delta);
        entry.setBalanceAfter(after);
        entry.setBizType("adjustment");
        entry.setBizKey("adjust:" + actorId + ":" + userId + ":" + before + ":" + delta + ":"
                + AFFlowOpsSupport.pythonFloat(timestamp));
        entry.setNote(body.reason());
        users.insertCoinLedger(entry);
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("coin_adjust");
        log.setTargetType("user");
        log.setTargetId(userId);
        log.setPayload(Json.write(AFFlowOpsSupport.map(
                "before", before, "delta", delta, "after", after, "reason", body.reason())));
        audit.insert(log);
        return AFFlowOpsSupport.map("before", before, "delta", delta, "after", after);
    }

    // ---- membership --------------------------------------------------------

    public Map<String, Object> userMembership(long userId) {
        UserRow account = users.findUser(userId);
        if (account == null) {
            throw ApiException.notFound("user not found");
        }
        Map<String, Object> payload = billing.membershipDetail(userId);
        payload.put("user", AFFlowOpsSupport.map(
                "id", account.getId(),
                "public_id", account.getPublicId(),
                "display_name", account.getDisplayName(),
                "email", account.getEmail()));
        Object last = payload.get("last_adjust");
        if (last instanceof Map<?, ?> lastAdjust && lastAdjust.get("actor_id") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> adjustMap = (Map<String, Object>) lastAdjust;
            long actorId = ((Number) lastAdjust.get("actor_id")).longValue();
            AdminUserRow staff = adminUsers.findById(actorId);
            if (staff != null) {
                String name = staff.getDisplayName() == null || staff.getDisplayName().isBlank()
                        ? staff.getUsername() : staff.getDisplayName();
                adjustMap.put("actor_name", name);
            }
        }
        return payload;
    }

    public Map<String, Object> grantMembership(long userId, AFFlowOpsBodies.MembershipGrantIn body, long actorId) {
        if (users.findUser(userId) == null) {
            throw ApiException.notFound("user not found");
        }
        return billing.grantMembership(userId, body, actorId);
    }

    public Map<String, Object> revokeMembership(long userId, AFFlowOpsBodies.MembershipRevokeIn body, long actorId) {
        if (users.findUser(userId) == null) {
            throw ApiException.notFound("user not found");
        }
        return billing.revokeMembership(userId, body, actorId);
    }

    private static void requireMaxLength(String value, int max) {
        if (value != null && value.length() > max) {
            throw ApiException.unprocessable("String should have at most " + max + " characters");
        }
    }
}
