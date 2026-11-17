package us.minitv.pcommerce.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.pcommerce.service.PCommerceWalletService;
import us.minitv.pcommerce.support.PCommerceRequests;
import us.minitv.security.CurrentUser;

import java.util.Map;

/** Coin unlock: POST /v1/unlock. */
@RestController
public class PCommerceWalletController {

    private final PCommerceWalletService wallet;

    public PCommerceWalletController(PCommerceWalletService wallet) {
        this.wallet = wallet;
    }

    public record UnlockIn(@JsonProperty("episode_id") Long episodeId) {
    }

    @PostMapping("/v1/unlock")
    public Map<String, Object> unlock(@RequestBody UnlockIn body,
                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                      String idempotencyKey,
                                      @CurrentUser UserRow user) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new ApiException(400, "Idempotency-Key required");
        }
        long episodeId = PCommerceRequests.required(body.episodeId(), "episode_id");
        return wallet.unlockEpisode(user.getId(), episodeId, idempotencyKey);
    }
}
