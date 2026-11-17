package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Coin reward configuration (/admin/v1/rewards/config). */
@RestController
public class AFFlowOpsRewardsController {

    private final AFFlowOpsRewardService rewards;

    public AFFlowOpsRewardsController(AFFlowOpsRewardService rewards) {
        this.rewards = rewards;
    }

    @GetMapping("/admin/v1/rewards/config")
    public Map<String, Object> readRewardConfig(@CurrentStaff StaffContext staff) {
        return rewards.getRewardConfig();
    }

    @PostMapping("/admin/v1/rewards/config")
    public Map<String, Object> saveRewardConfig(@CurrentStaff StaffContext staff,
                                                @RequestBody AFFlowOpsBodies.RewardIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        String actorName = staff.user().getDisplayName() == null || staff.user().getDisplayName().isBlank()
                ? staff.user().getUsername() : staff.user().getDisplayName();
        return rewards.saveRewardConfig(body, staff.user().getId(), actorName);
    }
}
