package us.minitv.paccount;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily coin tasks, ported from account/tasks.py.
 *
 * <p>A daily task pays once per America/Los_Angeles calendar day, progress only
 * counts that same day, and every row is keyed by (user, code, day) so a retry
 * can never pay twice. Progress counts <em>distinct</em> objects, not events.
 * Only tasks with an explicit daily period are served here; register / bind /
 * ad keep their own flows and are deliberately absent.</p>
 */
@Service
public class PAccountTasksService {

    private final PAccountTaskProgressMapper progress;
    private final PAccountRewardMapper rewards;
    private final PAccountRewardService rewardService;
    private final TransactionTemplate tx;

    public PAccountTasksService(PAccountTaskProgressMapper progress, PAccountRewardMapper rewards,
                                PAccountRewardService rewardService,
                                PlatformTransactionManager transactionManager) {
        this.progress = progress;
        this.rewards = rewards;
        this.rewardService = rewardService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * GET /v1/tasks and GET /v1/me/tasks: the task-centre payload, with or
     * without a session. A signed-out visitor gets zeroed progress.
     */
    public Map<String, Object> tasksOverview(UserRow user) {
        LocalDate day = Times.laToday();
        Long userId = user == null ? null : user.getId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (PAccountRewardCatalog.RewardTask task : PAccountRewardCatalog.DAILY_TASKS) {
            int taskProgress = userId == null ? 0 : taskProgress(userId, task, day);
            boolean claimed = userId != null
                    && rewards.findTaskClaim(userId, task.code(), day.toString()) != null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", task.code());
            item.put("name", task.name());
            item.put("description", task.description());
            item.put("metric", task.metric());
            item.put("target", task.target());
            item.put("progress", taskProgress);
            item.put("amount", rewardService.activeAmount(task.code(), day));
            item.put("claimed", claimed);
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("today", day.toString());
        payload.put("balance", userId == null ? 0 : rewardService.balance(userId));
        payload.put("checkin", rewardService.checkinState(userId, day));
        payload.put("items", items);
        return payload;
    }

    /** POST /v1/me/tasks/{code}/claim. */
    public Map<String, Object> claimTask(UserRow user, String code) {
        LocalDate day = Times.laToday();
        PAccountRewardCatalog.RewardTask task = PAccountRewardCatalog.byCode(code);
        if (task == null || !"daily".equals(task.period())) {
            throw ApiException.notFound("unknown daily task '" + code + "'");
        }
        long userId = user.getId();
        int taskProgress = taskProgress(userId, task, day);
        if (rewards.findTaskClaim(userId, code, day.toString()) != null) {
            return claimPayload(code, 0, rewardService.balance(userId), true, taskProgress);
        }
        int target = task.target() == null ? 0 : task.target();
        if (taskProgress < target) {
            throw ApiException.conflict("task not completed (" + taskProgress + "/" + target + ")");
        }
        // Read the amount at claim time: a change saved today only applies tomorrow.
        int amount = rewardService.activeAmount(code, day);
        try {
            return tx.execute(status -> {
                rewards.insertTaskClaim(userId, code, day.toString(), amount);
                int balance = rewardService.credit(userId, amount, code,
                        "task:" + code + ":" + userId + ":" + day);
                return claimPayload(code, amount, balance, false, taskProgress);
            });
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent claims: the unique key wins, the loser replays.
            // Python caught SQLAlchemy's IntegrityError, i.e. any constraint
            // violation raised by the claim insert / ledger insert pair.
            return claimPayload(code, 0, rewardService.balance(userId), true, taskProgress);
        }
    }

    private Map<String, Object> claimPayload(String code, int amount, int balance,
                                             boolean replayed, int progressCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("amount", amount);
        payload.put("balance", balance);
        payload.put("replayed", replayed);
        payload.put("progress", progressCount);
        return payload;
    }

    /** How many distinct objects the user touched inside the task's local day. */
    private int taskProgress(long userId, PAccountRewardCatalog.RewardTask task, LocalDate day) {
        LocalDateTime start = Times.dayStartUtc(day);
        LocalDateTime end = Times.dayStartUtc(day.plusDays(1));
        if ("complete".equals(task.metric())) {
            // A complete event with no position is a client bug or a forgery;
            // zero never counts.
            return progress.countCompletedEpisodes(userId, start, end);
        }
        return progress.countDramaReactions(userId, task.metric(), start, end);
    }
}
