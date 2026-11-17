package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.common.row.AuditLogRow;
import us.minitv.ops.row.RewardConfigRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coin reward configuration: the task catalog from account/service.py, the
 * current + pending amounts, and the next-day save with its audit trail.
 */
@Service
public class AFFlowOpsRewardService {

    static final long MAX_REWARD_AMOUNT = 1_000_000L;
    static final String REWARD_CONFIG_ACTION = "reward_config_update";

    /** One entry of account/service.py REWARD_TASKS. */
    private record Task(String code, String name, String description, String kind,
                        String period, String metric, Integer target, List<Integer> amounts) {
    }

    private static final List<Task> REWARD_TASKS = List.of(
            new Task("checkin", "7 天签到梯度", "连续签到第 1-7 天依次发放；中断后从第 1 天重新计算",
                    "gradient", "once", null, null, List.of(30, 30, 50, 50, 30, 60, 90)),
            new Task("register", "注册奖励", "邮箱注册成功后一次性发放",
                    "single", "once", null, null, List.of(80)),
            new Task("bind_email", "绑定邮箱", "首次绑定邮箱时发放，只发一次",
                    "single", "once", null, null, List.of(30)),
            new Task("bind_facebook", "绑定 Facebook", "首次绑定 Facebook 时发放，只发一次",
                    "single", "once", null, null, List.of(80)),
            new Task("ad", "每日广告奖励", "每天可领一次，同日重复请求不二次入账",
                    "single", "once", null, null, List.of(30)),
            new Task("like_5", "点赞 5 部剧", "当天给 5 部不同的剧点赞，每日可领一次",
                    "single", "daily", "like", 5, List.of(20)),
            new Task("favorite_5", "收藏 5 部剧", "当天收藏 5 部不同的剧，每日可领一次",
                    "single", "daily", "favorite", 5, List.of(30)),
            new Task("watch_8", "看完 8 集", "当天看完 8 集不同的剧集，每日可领一次",
                    "single", "daily", "complete", 8, List.of(50)));

    private final AFFlowOpsContentMapper content;
    private final AFFlowOpsAuditMapper audit;

    public AFFlowOpsRewardService(AFFlowOpsContentMapper content, AFFlowOpsAuditMapper audit) {
        this.content = content;
        this.audit = audit;
    }

    public Map<String, Object> getRewardConfig() {
        Map<String, Object> data = overview();
        data.put("recent_changes", recentChanges());
        return data;
    }

    private Map<String, Object> overview() {
        LocalDate today = Times.laToday();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Task task : REWARD_TASKS) {
            List<RewardConfigRow> rows = content.listRewardConfigs(task.code());
            RewardConfigRow current = null;
            RewardConfigRow pending = null;
            for (RewardConfigRow row : rows) {
                if (!row.getEffectiveOn().isAfter(today)) {
                    if (current == null) {
                        current = row;
                    }
                } else if (pending == null) {
                    pending = row;
                }
            }
            List<Integer> currentAmounts = current == null
                    ? List.copyOf(task.amounts())
                    : AFFlowOpsSupport.readIntList(current.getAmounts());
            items.add(AFFlowOpsSupport.map(
                    "code", task.code(),
                    "name", task.name(),
                    "description", task.description(),
                    "kind", task.kind(),
                    "period", task.period(),
                    "metric", task.metric(),
                    "target", task.target(),
                    "size", task.amounts().size(),
                    "default_amounts", List.copyOf(task.amounts()),
                    "current", AFFlowOpsSupport.map(
                            "amounts", currentAmounts,
                            "effective_on", current == null ? null : current.getEffectiveOn(),
                            "is_default", current == null),
                    "pending", pending == null ? null : AFFlowOpsSupport.map(
                            "amounts", AFFlowOpsSupport.readIntList(pending.getAmounts()),
                            "effective_on", pending.getEffectiveOn())));
        }
        return AFFlowOpsSupport.map(
                "today", today,
                "next_effective_on", today.plusDays(1),
                "items", items);
    }

    private List<Map<String, Object>> recentChanges() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditLogRow row : audit.recentRewardAudits(10)) {
            Map<String, Object> payload = AFFlowOpsSupport.readMap(row.getPayload());
            Object actor = payload.get("actor");
            if (actor == null || String.valueOf(actor).isEmpty()) {
                actor = row.getActorId() == null ? "system" : "#" + row.getActorId();
            }
            items.add(AFFlowOpsSupport.map(
                    "id", row.getId(),
                    "code", payload.get("code"),
                    "amounts", payload.get("amounts"),
                    "effective_on", payload.get("effective_on"),
                    "actor", actor,
                    "created_at", row.getCreatedAt()));
        }
        return items;
    }

    @Transactional
    public Map<String, Object> saveRewardConfig(AFFlowOpsBodies.RewardIn body, long actorId, String actorName) {
        if (body == null || body.code() == null || body.amounts() == null) {
            throw ApiException.unprocessable("Field required");
        }
        if (body.code().isEmpty() || body.code().length() > 32) {
            throw ApiException.unprocessable("String should have between 1 and 32 characters");
        }
        if (body.amounts().isEmpty() || body.amounts().size() > 7) {
            throw ApiException.unprocessable("List should have between 1 and 7 items");
        }
        List<Integer> cleaned = validateAmounts(body.code(), body.amounts());
        LocalDate today = Times.laToday();
        LocalDate effectiveOn = today.plusDays(1);
        RewardConfigRow row = content.findRewardConfig(body.code(), effectiveOn);
        String amountsJson = us.minitv.common.Json.write(cleaned);
        if (row == null) {
            row = new RewardConfigRow();
            row.setCode(body.code());
            row.setAmounts(amountsJson);
            row.setEffectiveOn(effectiveOn);
            content.insertRewardConfig(row);
        } else {
            row.setAmounts(amountsJson);
            content.updateRewardConfigAmounts(row.getId(), amountsJson);
        }
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction(REWARD_CONFIG_ACTION);
        log.setTargetType("reward_config");
        log.setTargetId(row.getId());
        log.setPayload(us.minitv.common.Json.write(AFFlowOpsSupport.map(
                "code", row.getCode(),
                "amounts", cleaned,
                "effective_on", effectiveOn,
                "actor", actorName)));
        audit.insert(log);
        return AFFlowOpsSupport.map(
                "code", row.getCode(),
                "amounts", cleaned,
                "effective_on", effectiveOn);
    }

    private static List<Integer> validateAmounts(String code, List<Integer> amounts) {
        Task task = null;
        for (Task candidate : REWARD_TASKS) {
            if (candidate.code().equals(code)) {
                task = candidate;
                break;
            }
        }
        if (task == null) {
            throw ApiException.unprocessable("unknown reward code '" + code + "'");
        }
        List<Integer> cleaned = new ArrayList<>();
        for (Integer value : amounts) {
            if (value == null || value < 1 || value > MAX_REWARD_AMOUNT) {
                throw ApiException.unprocessable("amounts must be between 1 and " + MAX_REWARD_AMOUNT);
            }
            cleaned.add(value);
        }
        if (cleaned.size() != task.amounts().size()) {
            throw ApiException.unprocessable(code + " expects " + task.amounts().size()
                    + " amount(s), got " + cleaned.size());
        }
        return cleaned;
    }
}
