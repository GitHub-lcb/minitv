package us.minitv.paccount;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.security.CurrentUser;

import java.util.Map;

/**
 * Signed-in "me" endpoints, ported from modules/public_flow.py:
 *
 * <ul>
 *   <li>GET   /v1/me</li>
 *   <li>PATCH /v1/me</li>
 *   <li>GET   /v1/me/ledger</li>
 *   <li>POST  /v1/me/checkin</li>
 *   <li>POST  /v1/me/ad-reward</li>
 *   <li>POST  /v1/me/reminder</li>
 * </ul>
 *
 * <p>{@code @CurrentUser} mirrors FastAPI's {@code require_user}: a missing or
 * unknown bearer token answers 401 with detail {@code "login required"}.</p>
 */
@RestController
public class PAccountMeController {

    private final PAccountMeService me;
    private final PAccountRewardService rewards;

    public PAccountMeController(PAccountMeService me, PAccountRewardService rewards) {
        this.me = me;
        this.rewards = rewards;
    }

    @GetMapping("/v1/me")
    public Map<String, Object> me(@CurrentUser UserRow user) {
        return me.me(user);
    }

    @PatchMapping("/v1/me")
    public Map<String, Object> updateMe(@CurrentUser UserRow user,
                                        @RequestBody PAccountProfileIn body) {
        return me.updateProfile(user, body);
    }

    @GetMapping("/v1/me/ledger")
    public Map<String, Object> ledger(@CurrentUser UserRow user) {
        return me.ledger(user);
    }

    @PostMapping("/v1/me/checkin")
    public Map<String, Object> checkin(@CurrentUser UserRow user) {
        return rewards.checkIn(user);
    }

    @PostMapping("/v1/me/ad-reward")
    public Map<String, Object> adReward(@CurrentUser UserRow user) {
        return rewards.claimAd(user);
    }

    /** FastAPI took {@code enabled: bool} as a required query parameter. */
    @PostMapping("/v1/me/reminder")
    public Map<String, Object> reminder(@CurrentUser UserRow user,
                                        @RequestParam("enabled") boolean enabled) {
        return me.setReminder(user, enabled);
    }
}
