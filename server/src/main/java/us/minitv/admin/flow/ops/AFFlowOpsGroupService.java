package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaHomeGroupRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.HomeGroupRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Homepage groups: create/list/edit, membership add/remove/reorder. */
@Service
public class AFFlowOpsGroupService {

    static final int GROUP_LIMIT = 100;

    private final AFFlowOpsContentMapper content;

    public AFFlowOpsGroupService(AFFlowOpsContentMapper content) {
        this.content = content;
    }

    @Transactional
    public Map<String, Object> createGroup(AFFlowOpsBodies.GroupIn body) {
        if (body == null || body.name() == null) {
            throw ApiException.unprocessable("Field required");
        }
        HomeGroupRow row = new HomeGroupRow();
        row.setName(body.name());
        row.setPosition(body.position() == null ? 0 : body.position());
        row.setStatus("active");
        row.setStyle("poster3");
        row.setBadge("rank");
        content.insertGroup(row);
        return AFFlowOpsSupport.map("id", row.getId());
    }

    public Map<String, Object> listGroups() {
        List<HomeGroupRow> rows = content.listGroups();
        Map<Long, Long> counts = groupCounts();
        List<Map<String, Object>> items = new ArrayList<>();
        for (HomeGroupRow row : rows) {
            items.add(groupOut(row, counts.getOrDefault(row.getId(), 0L)));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    @Transactional
    public Map<String, Object> updateGroup(long groupId, Map<String, Object> body) {
        HomeGroupRow group = content.findGroup(groupId);
        if (group == null) {
            throw ApiException.notFound("group not found");
        }
        Map<String, Object> data = body == null ? Map.of() : body;
        if (data.isEmpty()) {
            throw ApiException.unprocessable("nothing to update");
        }
        // Pydantic validated these before the handler ran; mirror the same 422s.
        if (data.containsKey("status") && data.get("status") != null) {
            String status = String.valueOf(data.get("status"));
            if (!"active".equals(status) && !"inactive".equals(status)) {
                throw ApiException.unprocessable("status must be active or inactive");
            }
        }
        if (data.containsKey("style") && data.get("style") != null) {
            String style = String.valueOf(data.get("style"));
            if (!"poster3".equals(style) && !"rank".equals(style) && !"grid2".equals(style)) {
                throw ApiException.unprocessable("style must be poster3, rank or grid2");
            }
        }
        if (data.containsKey("badge") && data.get("badge") != null) {
            String badge = String.valueOf(data.get("badge"));
            if (!"rank".equals(badge) && !"label".equals(badge)) {
                throw ApiException.unprocessable("badge must be rank or label");
            }
        }
        boolean changed = false;
        if (data.containsKey("name")) {
            String name = data.get("name") == null ? "" : String.valueOf(data.get("name")).trim();
            if (name.isEmpty()) {
                throw ApiException.unprocessable("name is required");
            }
            if (name.length() > 64) {
                throw ApiException.unprocessable("String should have at most 64 characters");
            }
            group.setName(name);
            changed = true;
        }
        if (data.get("status") != null) {
            group.setStatus(String.valueOf(data.get("status")));
            changed = true;
        }
        if (data.get("position") != null) {
            group.setPosition(AFFlowOpsSupport.toInt(data.get("position")));
            changed = true;
        }
        if (data.get("style") != null) {
            group.setStyle(String.valueOf(data.get("style")));
            changed = true;
        }
        if (data.get("badge") != null) {
            group.setBadge(String.valueOf(data.get("badge")));
            changed = true;
        }
        if (changed) {
            content.updateGroup(group);
        }
        long count = content.countGroupDramas(group.getId());
        return groupOut(group, count);
    }

    @Transactional
    public Map<String, Object> addToGroup(long groupId, AFFlowOpsBodies.GroupAddIn body) {
        HomeGroupRow group = content.findGroup(groupId);
        if (group == null) {
            throw ApiException.notFound("group not found");
        }
        if (body == null || body.drama_id() == null) {
            throw ApiException.unprocessable("Field required");
        }
        long dramaId = body.drama_id();
        long count = content.countGroupDramas(groupId);
        DramaHomeGroupRow existing = content.findLink(groupId, dramaId);
        boolean confirm = body.confirm() != null && body.confirm();
        boolean alreadyInGroup = existing != null;
        if (!confirm) {
            return AFFlowOpsSupport.map(
                    "written", false,
                    "selected", count,
                    "limit", GROUP_LIMIT,
                    "already_in_group", alreadyInGroup);
        }
        if (alreadyInGroup) {
            return AFFlowOpsSupport.map(
                    "written", false,
                    "reason", "already_in_group",
                    "selected", count,
                    "limit", GROUP_LIMIT,
                    "already_in_group", true);
        }
        if (count >= GROUP_LIMIT) {
            throw new ApiException(409, "group is full");
        }
        DramaHomeGroupRow link = new DramaHomeGroupRow();
        link.setGroupId(groupId);
        link.setDramaId(dramaId);
        link.setPositionInGroup((int) count);
        content.insertLink(link);
        return AFFlowOpsSupport.map(
                "written", true,
                "selected", count + 1,
                "limit", GROUP_LIMIT);
    }

    public Map<String, Object> groupDramas(long groupId) {
        HomeGroupRow group = content.findGroup(groupId);
        if (group == null) {
            throw ApiException.notFound("group not found");
        }
        List<DramaHomeGroupRow> links = content.listLinks(groupId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaHomeGroupRow link : links) {
            DramaRow drama = content.findDrama(link.getDramaId());
            if (drama == null) {
                continue;
            }
            items.add(AFFlowOpsSupport.map(
                    "id", drama.getId(),
                    "title", drama.getTitle(),
                    "status", drama.getStatus(),
                    "cover_url", drama.getCoverUrl(),
                    "position_in_group", link.getPositionInGroup()));
        }
        return AFFlowOpsSupport.map("group", groupOut(group, items.size()), "items", items);
    }

    @Transactional
    public Map<String, Object> removeFromGroup(long groupId, long dramaId) {
        if (content.findGroup(groupId) == null) {
            throw ApiException.notFound("group not found");
        }
        int deleted = content.deleteLink(groupId, dramaId);
        if (deleted == 0) {
            throw ApiException.notFound("not in group");
        }
        return AFFlowOpsSupport.map("removed", true);
    }

    @Transactional
    public Map<String, Object> setGroupPosition(long groupId, long dramaId, AFFlowOpsBodies.PositionIn body) {
        DramaHomeGroupRow link = content.findLink(groupId, dramaId);
        if (link == null) {
            throw ApiException.notFound("not in group");
        }
        if (body == null || body.position() == null) {
            throw ApiException.unprocessable("Field required");
        }
        content.updateLinkPosition(groupId, dramaId, body.position());
        return AFFlowOpsSupport.map("position_in_group", body.position());
    }

    public Map<String, Object> dramaGroups(long dramaId) {
        if (content.findDrama(dramaId) == null) {
            throw ApiException.notFound("drama not found");
        }
        List<HomeGroupRow> rows = content.listGroups();
        Map<Long, Long> counts = groupCounts();
        Map<Long, Integer> links = new HashMap<>();
        for (DramaHomeGroupRow link : content.listLinksByDrama(dramaId)) {
            links.put(link.getGroupId(), link.getPositionInGroup());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (HomeGroupRow row : rows) {
            Map<String, Object> item = groupOut(row, counts.getOrDefault(row.getId(), 0L));
            item.put("joined", links.containsKey(row.getId()));
            item.put("position_in_group", links.get(row.getId()));
            items.add(item);
        }
        return AFFlowOpsSupport.map("items", items);
    }

    private Map<Long, Long> groupCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : content.groupCounts()) {
            counts.put(AFFlowOpsSupport.asLong(row.get("group_id")),
                    AFFlowOpsSupport.asLong(row.get("cnt")));
        }
        return counts;
    }

    static Map<String, Object> groupOut(HomeGroupRow row, long dramaCount) {
        return AFFlowOpsSupport.map(
                "id", row.getId(),
                "name", row.getName(),
                "position", row.getPosition(),
                "status", row.getStatus(),
                "style", row.getStyle(),
                "badge", row.getBadge(),
                "drama_count", dramaCount,
                "limit", GROUP_LIMIT);
    }
}
