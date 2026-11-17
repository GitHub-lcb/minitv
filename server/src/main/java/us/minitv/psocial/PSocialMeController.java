package us.minitv.psocial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.security.CurrentUser;

import java.util.Map;

/**
 * The signed-in viewer's profile shelves (public-social module): favorites,
 * inbox messages, watch history and reaction lists.
 */
@RestController
public class PSocialMeController {

    private final PSocialMeService me;

    public PSocialMeController(PSocialMeService me) {
        this.me = me;
    }

    @GetMapping("/v1/me/favorites")
    public Map<String, Object> favorites(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @CurrentUser UserRow user) {
        return me.favorites(limit, offset, user);
    }

    @GetMapping("/v1/me/messages")
    public Map<String, Object> messages(@CurrentUser UserRow user) {
        return me.messages(user);
    }

    @PostMapping("/v1/me/messages/read")
    public Map<String, Object> readMessages(@CurrentUser UserRow user) {
        return me.markMessagesRead(user);
    }

    @GetMapping("/v1/me/history")
    public Map<String, Object> history(@CurrentUser UserRow user) {
        return me.history(user);
    }

    @GetMapping("/v1/me/lists/{kind}")
    public Map<String, Object> list(@PathVariable("kind") String kind, @CurrentUser UserRow user) {
        return me.list(kind, user);
    }
}
