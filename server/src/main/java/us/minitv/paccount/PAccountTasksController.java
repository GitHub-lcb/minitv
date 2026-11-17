package us.minitv.paccount;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.security.CurrentUser;

import java.util.Map;

/**
 * Daily task centre, ported from modules/public_flow.py:
 *
 * <ul>
 *   <li>GET  /v1/tasks (signed-out; same cards with zeroed progress)</li>
 *   <li>GET  /v1/me/tasks</li>
 *   <li>POST /v1/me/tasks/{code}/claim</li>
 * </ul>
 */
@RestController
public class PAccountTasksController {

    private final PAccountTasksService tasks;

    public PAccountTasksController(PAccountTasksService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/v1/tasks")
    public Map<String, Object> publicTasks() {
        return tasks.tasksOverview(null);
    }

    @GetMapping("/v1/me/tasks")
    public Map<String, Object> myTasks(@CurrentUser UserRow user) {
        return tasks.tasksOverview(user);
    }

    @PostMapping("/v1/me/tasks/{code}/claim")
    public Map<String, Object> claim(@CurrentUser UserRow user,
                                     @PathVariable("code") String code) {
        return tasks.claimTask(user, code);
    }
}
