package us.minitv.paccount;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.ops.row.AdClaimRow;
import us.minitv.ops.row.CheckinRow;
import us.minitv.ops.row.RewardConfigRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Check-in, ad reward and the shared coin-credit primitive, ported from
 * account/service.py (check_in / claim_ad / _credit / _active_amount /
 * _checkin_state).
 *
 * <p>All amounts are read at claim time so a config saved today only applies
 * tomorrow; every payout writes one coin_ledger row keyed by a globally unique
 * biz_key, which is what makes retries impossible to double-book.</p>
 */
@Service
public class PAccountRewardService {

    private final PAccountRewardMapper rewards;
    private final PAccountWalletMapper wallets;
    private final PAccountLedgerMapper ledger;
    private final TransactionTemplate tx;

    public PAccountRewardService(PAccountRewardMapper rewards, PAccountWalletMapper wallets,
                                 PAccountLedgerMapper ledger,
                                 PlatformTransactionManager transactionManager) {
        this.rewards = rewards;
        this.wallets = wallets;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Python {@code _active_amount}: first amount of the newest effective config. */
    public int activeAmount(String code, LocalDate day) {
        RewardConfigRow row = rewards.findActiveConfig(code, day);
        List<Integer> effective = row == null
                ? PAccountRewardCatalog.defaultAmounts(code) : amounts(row.getAmounts());
        return effective.get(0);
    }

    /** Python {@code checkin gradient}: configured amounts or the 7-day default. */
    public List<Integer> checkinGradient(LocalDate day) {
        RewardConfigRow row = rewards.findActiveConfig("checkin", day);
        return row == null ? PAccountRewardCatalog.DEFAULT_CHECKIN : amounts(row.getAmounts());
    }

    /** JSON column as a list; an empty/invalid payload stays empty like Python's list(). */
    private static List<Integer> amounts(String raw) {
        List<Integer> parsed = Json.read(raw, new TypeReference<List<Integer>>() {
        }, null);
        return parsed == null ? List.of() : parsed;
    }

    /** Python tasks.py {@code _balance}: a missing wallet reads as 0. */
    public int balance(long userId) {
        WalletAccountRow wallet = wallets.findById(userId);
        return wallet == null ? 0 : wallet.getBalance();
    }

    /**
     * Credit coins inside the caller's transaction: row lock on the wallet,
     * materialised balance update and one ledger entry. Returns the new balance.
     */
    public int credit(long userId, int amount, String bizType, String bizKey) {
        WalletAccountRow wallet = wallets.findForUpdate(userId);
        if (wallet == null) {
            wallets.insert(userId, 0);
            wallet = new WalletAccountRow();
            wallet.setUserId(userId);
            wallet.setBalance(0);
        }
        int balance = wallet.getBalance() + amount;
        wallets.updateBalance(userId, balance);
        ledger.insert(userId, amount, balance, bizType, bizKey);
        return balance;
    }

    /** POST /v1/me/checkin: one payout per Los Angeles calendar day, streak resets at 8. */
    public Map<String, Object> checkIn(UserRow user) {
        LocalDate today = Times.laToday();
        return tx.execute(status -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            CheckinRow existing = rewards.findCheckin(user.getId(), today);
            if (existing != null) {
                payload.put("streak", existing.getStreak());
                payload.put("amount", 0);
                payload.put("replayed", true);
                payload.put("balance", balance(user.getId()));
                return payload;
            }
            CheckinRow yesterday = rewards.findCheckin(user.getId(), today.minusDays(1));
            int streak = yesterday == null ? 1 : yesterday.getStreak() + 1;
            if (streak > 7) {
                streak = 1;
            }
            List<Integer> gradient = checkinGradient(today);
            int amount = gradient.get((streak - 1) % 7);
            rewards.insertCheckin(user.getId(), today, streak, amount);
            int balance = credit(user.getId(), amount, "checkin",
                    "checkin:" + user.getId() + ":" + today);
            payload.put("streak", streak);
            payload.put("amount", amount);
            payload.put("replayed", false);
            payload.put("balance", balance);
            return payload;
        });
    }

    /** POST /v1/me/ad-reward: one claim per Los Angeles day. */
    public Map<String, Object> claimAd(UserRow user) {
        LocalDate today = Times.laToday();
        return tx.execute(status -> {
            if (rewards.findAdClaim(user.getId(), today) != null) {
                throw ApiException.conflict("ad reward already claimed");
            }
            int amount = activeAmount("ad", today);
            rewards.insertAdClaim(user.getId(), today, amount);
            int balance = credit(user.getId(), amount, "ad_reward",
                    "ad:" + user.getId() + ":" + today);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", amount);
            payload.put("balance", balance);
            return payload;
        });
    }

    /**
     * Python tasks.py {@code _checkin_state}: the check-in card without guessing.
     * Signed-out visitors get the day-one offer with a zeroed streak.
     */
    public Map<String, Object> checkinState(Long userId, LocalDate day) {
        List<Integer> gradient = checkinGradient(day);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (userId == null) {
            payload.put("checked_today", false);
            payload.put("streak", 1);
            payload.put("amount", gradient.get(0));
            return payload;
        }
        CheckinRow todayRow = rewards.findCheckin(userId, day);
        if (todayRow != null) {
            payload.put("checked_today", true);
            payload.put("streak", todayRow.getStreak());
            payload.put("amount", todayRow.getAmount());
            return payload;
        }
        CheckinRow yesterday = rewards.findCheckin(userId, day.minusDays(1));
        int streak = yesterday == null ? 1 : yesterday.getStreak() + 1;
        if (streak > 7) {
            streak = 1;
        }
        payload.put("checked_today", false);
        payload.put("streak", streak);
        payload.put("amount", gradient.get((streak - 1) % 7));
        return payload;
    }
}
