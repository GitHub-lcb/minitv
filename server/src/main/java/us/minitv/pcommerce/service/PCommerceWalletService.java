package us.minitv.pcommerce.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.IdempotencyRecordRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.pcommerce.mapper.PCommerceIdempotencyMapper;
import us.minitv.pcommerce.mapper.PCommerceWalletMapper;
import us.minitv.pcommerce.support.PCommerceCrypto;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.EntitlementRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wallet service: idempotent coin unlock with ledger and entitlement writes,
 * ported from {@code app/modules/wallet/service.py}.
 *
 * <p>Order of checks: idempotent replay first, then member priority (an active
 * subscription never creates a coin flow), then an existing entitlement (covers
 * retries with a different key), then the charge inside one transaction with a
 * row lock. Insufficient balance leaves zero partial writes behind.</p>
 */
@Service
public class PCommerceWalletService {

    private static final String UNLOCK_ENDPOINT = "unlock";
    private static final String ENTITLEMENT_TYPE_COIN = "coin";

    private final PCommerceWalletMapper wallet;
    private final PCommerceIdempotencyMapper idempotency;
    private final PCommerceContentService content;
    private final PCommerceBillingService billing;

    public PCommerceWalletService(PCommerceWalletMapper wallet,
                                  PCommerceIdempotencyMapper idempotency,
                                  PCommerceContentService content,
                                  PCommerceBillingService billing) {
        this.wallet = wallet;
        this.idempotency = idempotency;
        this.content = content;
        this.billing = billing;
    }

    /** unlock_episode: returns status/source/episode_id/balance_after/replayed. */
    @Transactional
    public Map<String, Object> unlockEpisode(long userId, long episodeId, String idempotencyKey) {
        // Keys are client-generated, so scope them to the caller: a key reused by
        // a different account must never replay someone else's outcome.
        String scopedKey = scopedKey(userId, idempotencyKey);

        // 1. idempotent replay: return the first outcome untouched
        IdempotencyRecordRow record = idempotency.findByKey(scopedKey);
        if (record != null) {
            Map<String, Object> payload = new LinkedHashMap<>(Json.readMap(record.getResponsePayload()));
            payload.put("replayed", true);
            return payload;
        }

        // 2. resolve episode + price; only published content is purchasable
        EpisodeRow episode = content.findEpisode(episodeId);
        DramaRow drama = episode == null ? null : content.findDrama(episode.getDramaId());
        if (episode == null || drama == null
                || !"published".equals(episode.getStatus())
                || !"published".equals(drama.getStatus())) {
            throw new ApiException(404, "episode not found");
        }
        int price = drama.getCoinPricePerEpisode() == null ? 0 : drama.getCoinPricePerEpisode();
        if (price <= 0) {
            // A zero price would write amount=0 and violate the ledger constraint.
            throw new ApiException(409, "episode is not purchasable");
        }

        // 3. member priority: an active subscription never creates a coin flow
        if (billing.accessUntil(userId) != null) {
            Map<String, Object> result = unlockResult("subscription", "subscription", episodeId, null, false);
            recordIdempotency(scopedKey, result);
            return result;
        }

        // 4. already unlocked? (also covers a retry with a different key)
        EntitlementRow existing = wallet.findEntitlement(userId, episodeId, ENTITLEMENT_TYPE_COIN);
        if (existing != null) {
            Integer balance = wallet.findBalance(userId);
            Map<String, Object> result = unlockResult("already_unlocked", "coin", episodeId,
                    balance == null ? 0 : balance, false);
            recordIdempotency(scopedKey, result);
            return result;
        }

        // 5. charge inside one transaction: row lock, nonnegative guard, ledger + entitlement
        WalletAccountRow account = wallet.lockWallet(userId);
        int have = account == null || account.getBalance() == null ? 0 : account.getBalance();
        if (account == null || account.getBalance() < price) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("need", price);
            detail.put("have", have);
            throw new ApiException(402, detail);
        }
        int balanceAfter = account.getBalance() - price;
        wallet.updateBalance(userId, balanceAfter);

        CoinLedgerRow ledger = new CoinLedgerRow();
        ledger.setUserId(userId);
        ledger.setAmount(-price);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("unlock");
        ledger.setBizKey(scopedKey);
        ledger.setEpisodeId(episodeId);
        ledger.setNote("");
        wallet.insertLedger(ledger);
        wallet.insertEntitlement(userId, episodeId, ENTITLEMENT_TYPE_COIN);

        Map<String, Object> result = unlockResult("unlocked", "coin", episodeId, balanceAfter, false);
        recordIdempotency(scopedKey, result);
        return result;
    }

    private Map<String, Object> unlockResult(String status, String source, long episodeId,
                                             Integer balanceAfter, boolean replayed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("source", source);
        result.put("episode_id", episodeId);
        result.put("balance_after", balanceAfter);
        result.put("replayed", replayed);
        return result;
    }

    private void recordIdempotency(String scopedKey, Map<String, Object> result) {
        IdempotencyRecordRow row = new IdempotencyRecordRow();
        row.setIdempotencyKey(scopedKey);
        row.setEndpoint(UNLOCK_ENDPOINT);
        row.setResponsePayload(Json.write(result));
        idempotency.insert(row);
    }

    private static String scopedKey(long userId, String idempotencyKey) {
        String digest = PCommerceCrypto.sha256Hex(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return "u" + userId + ":" + digest;
    }
}
