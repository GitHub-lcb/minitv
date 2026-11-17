package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** End-user administration: listing, profile edits, coins and membership. */
@RestController
public class AFFlowOpsUsersController {

    private final AFFlowOpsUserService users;

    public AFFlowOpsUsersController(AFFlowOpsUserService users) {
        this.users = users;
    }

    @GetMapping("/admin/v1/users")
    public Map<String, Object> listUsers(@CurrentStaff StaffContext staff,
                                         @RequestParam(value = "q", required = false) String q) {
        return users.listUsers(q);
    }

    @PatchMapping("/admin/v1/users/{user_id}")
    public Map<String, Object> updateUser(@CurrentStaff StaffContext staff,
                                          @PathVariable("user_id") long userId,
                                          @RequestBody Map<String, Object> body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return users.updateUser(userId, body, staff.user().getId());
    }

    @PostMapping("/admin/v1/users/{user_id}/coins")
    public Map<String, Object> adjustCoins(@CurrentStaff StaffContext staff,
                                           @PathVariable("user_id") long userId,
                                           @RequestBody AFFlowOpsBodies.AdjustIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return users.adjustCoins(userId, body, staff.user().getId());
    }

    @GetMapping("/admin/v1/users/{user_id}/membership")
    public Map<String, Object> userMembership(@CurrentStaff StaffContext staff,
                                              @PathVariable("user_id") long userId) {
        return users.userMembership(userId);
    }

    @PostMapping("/admin/v1/users/{user_id}/membership")
    public Map<String, Object> grantMembership(@CurrentStaff StaffContext staff,
                                               @PathVariable("user_id") long userId,
                                               @RequestBody AFFlowOpsBodies.MembershipGrantIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return users.grantMembership(userId, body, staff.user().getId());
    }

    @DeleteMapping("/admin/v1/users/{user_id}/membership")
    public Map<String, Object> revokeMembership(@CurrentStaff StaffContext staff,
                                                @PathVariable("user_id") long userId,
                                                @RequestBody AFFlowOpsBodies.MembershipRevokeIn body) {
        AFFlowOpsSupport.requireAdminRole(staff);
        return users.revokeMembership(userId, body, staff.user().getId());
    }
}
