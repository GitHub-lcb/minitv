package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Homepage group management (/admin/v1/groups, /admin/v1/dramas/{id}/groups). */
@RestController
public class AFFlowOpsGroupsController {

    private final AFFlowOpsGroupService groups;

    public AFFlowOpsGroupsController(AFFlowOpsGroupService groups) {
        this.groups = groups;
    }

    @PostMapping("/admin/v1/groups")
    public Map<String, Object> createGroup(@CurrentStaff StaffContext staff,
                                           @RequestBody AFFlowOpsBodies.GroupIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return groups.createGroup(body);
    }

    @GetMapping("/admin/v1/groups")
    public Map<String, Object> listGroups(@CurrentStaff StaffContext staff) {
        return groups.listGroups();
    }

    @PatchMapping("/admin/v1/groups/{group_id}")
    public Map<String, Object> updateGroup(@CurrentStaff StaffContext staff,
                                           @PathVariable("group_id") long groupId,
                                           @RequestBody Map<String, Object> body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return groups.updateGroup(groupId, body);
    }

    @PostMapping("/admin/v1/groups/{group_id}/dramas")
    public Map<String, Object> addDrama(@CurrentStaff StaffContext staff,
                                        @PathVariable("group_id") long groupId,
                                        @RequestBody AFFlowOpsBodies.GroupAddIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return groups.addToGroup(groupId, body);
    }

    @GetMapping("/admin/v1/groups/{group_id}/dramas")
    public Map<String, Object> groupDramas(@CurrentStaff StaffContext staff,
                                           @PathVariable("group_id") long groupId) {
        return groups.groupDramas(groupId);
    }

    @DeleteMapping("/admin/v1/groups/{group_id}/dramas/{drama_id}")
    public Map<String, Object> removeDrama(@CurrentStaff StaffContext staff,
                                           @PathVariable("group_id") long groupId,
                                           @PathVariable("drama_id") long dramaId) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return groups.removeFromGroup(groupId, dramaId);
    }

    @PatchMapping("/admin/v1/groups/{group_id}/dramas/{drama_id}")
    public Map<String, Object> positionDrama(@CurrentStaff StaffContext staff,
                                             @PathVariable("group_id") long groupId,
                                             @PathVariable("drama_id") long dramaId,
                                             @RequestBody AFFlowOpsBodies.PositionIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return groups.setGroupPosition(groupId, dramaId, body);
    }

    @GetMapping("/admin/v1/dramas/{drama_id}/groups")
    public Map<String, Object> dramaGroups(@CurrentStaff StaffContext staff,
                                           @PathVariable("drama_id") long dramaId) {
        return groups.dramaGroups(dramaId);
    }
}
