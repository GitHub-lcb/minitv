package us.minitv.paccount;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coin-task catalogue ported 1:1 from account/service.py.
 *
 * <p>The Python module keeps one tuple of task dicts as the single source of
 * truth: the admin console renders it as-is and the client reward screen reuses
 * the same codes. Only tasks with an explicit {@code period="daily"} are served
 * by /v1/tasks and /v1/me/tasks; the register / bind / ad tasks keep their own
 * flows and are deliberately absent from the daily task centre.</p>
 */
public final class PAccountRewardCatalog {

    /** Default seven-day check-in gradient (account/service.py DEFAULT_CHECKIN). */
    public static final List<Integer> DEFAULT_CHECKIN = List.of(30, 30, 50, 50, 30, 60, 90);

    /** Default one-off amounts per task code (account/service.py DEFAULT_AMOUNTS). */
    public static final Map<String, List<Integer>> DEFAULT_AMOUNTS;

    static {
        Map<String, List<Integer>> defaults = new LinkedHashMap<>();
        defaults.put("register", List.of(80));
        defaults.put("bind_email", List.of(30));
        defaults.put("bind_facebook", List.of(80));
        defaults.put("ad", List.of(30));
        defaults.put("like_5", List.of(20));
        defaults.put("favorite_5", List.of(30));
        defaults.put("watch_8", List.of(50));
        DEFAULT_AMOUNTS = Map.copyOf(defaults);
    }

    /**
     * One task definition. {@code period}, {@code metric} and {@code target} are
     * typed nullable because the Python dicts simply omit those keys for the
     * one-off tasks.
     */
    public record RewardTask(String code, String name, String description, String kind,
                             String period, String metric, Integer target, List<Integer> amounts) {
    }

    public static final List<RewardTask> REWARD_TASKS = List.of(
            new RewardTask("checkin", "7 天签到梯度",
                    "连续签到第 1-7 天依次发放；中断后从第 1 天重新计算",
                    "gradient", null, null, null, DEFAULT_CHECKIN),
            new RewardTask("register", "注册奖励",
                    "邮箱注册成功后一次性发放",
                    "single", null, null, null, DEFAULT_AMOUNTS.get("register")),
            new RewardTask("bind_email", "绑定邮箱",
                    "首次绑定邮箱时发放，只发一次",
                    "single", null, null, null, DEFAULT_AMOUNTS.get("bind_email")),
            new RewardTask("bind_facebook", "绑定 Facebook",
                    "首次绑定 Facebook 时发放，只发一次",
                    "single", null, null, null, DEFAULT_AMOUNTS.get("bind_facebook")),
            new RewardTask("ad", "每日广告奖励",
                    "每天可领一次，同日重复请求不二次入账",
                    "single", null, null, null, DEFAULT_AMOUNTS.get("ad")),
            new RewardTask("like_5", "点赞 5 部剧",
                    "当天给 5 部不同的剧点赞，每日可领一次",
                    "single", "daily", "like", 5, DEFAULT_AMOUNTS.get("like_5")),
            new RewardTask("favorite_5", "收藏 5 部剧",
                    "当天收藏 5 部不同的剧，每日可领一次",
                    "single", "daily", "favorite", 5, DEFAULT_AMOUNTS.get("favorite_5")),
            new RewardTask("watch_8", "看完 8 集",
                    "当天看完 8 集不同的剧集，每日可领一次",
                    "single", "daily", "complete", 8, DEFAULT_AMOUNTS.get("watch_8")));

    /** Only tasks with an explicit daily period are served by the task centre. */
    public static final List<RewardTask> DAILY_TASKS = REWARD_TASKS.stream()
            .filter(task -> "daily".equals(task.period()))
            .toList();

    private static final Map<String, RewardTask> BY_CODE;

    static {
        Map<String, RewardTask> byCode = new LinkedHashMap<>();
        for (RewardTask task : REWARD_TASKS) {
            byCode.put(task.code(), task);
        }
        BY_CODE = Map.copyOf(byCode);
    }

    private PAccountRewardCatalog() {
    }

    /** Null when {@code code} is not one of the catalogued tasks. */
    public static RewardTask byCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** Python: {@code DEFAULT_AMOUNTS.get(code, DEFAULT_CHECKIN)}. */
    public static List<Integer> defaultAmounts(String code) {
        return DEFAULT_AMOUNTS.getOrDefault(code, DEFAULT_CHECKIN);
    }
}
