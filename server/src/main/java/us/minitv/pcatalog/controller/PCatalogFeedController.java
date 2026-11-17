package us.minitv.pcatalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.pcatalog.service.PCatalogFeedService;
import us.minitv.pcatalog.support.PCatalogFeedConfig;
import us.minitv.security.CurrentUser;

import java.util.Locale;
import java.util.Map;

/** GET /v1/feed - the For-You vertical feed. */
@RestController
public class PCatalogFeedController {

    private final PCatalogFeedService feedService;

    public PCatalogFeedController(PCatalogFeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * One page of the For-You feed.
     *
     * <p>{@code cursor} is the opaque pointer from the previous page (omit it to
     * start a new session - that is also what pull-to-refresh sends);
     * {@code limit} is 1..20, default 5; {@code country} is an ISO code used for
     * the drama region allowlist. Identity is the bearer token when signed in,
     * otherwise {@code X-Device-Id}, so guests still get a stable session.
     */
    @GetMapping("/v1/feed")
    public Map<String, Object> feed(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "country", required = false) String country,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @CurrentUser(required = false) UserRow user) {

        if (limit != null && (limit < 1 || limit > PCatalogFeedConfig.MAX_PAGE_SIZE)) {
            // FastAPI/pydantic Query(ge=1, le=20) emitted this exact 422 entry.
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            boolean tooSmall = limit < 1;
            item.put("type", tooSmall ? "greater_than_equal" : "less_than_equal");
            item.put("loc", java.util.List.of("query", "limit"));
            item.put("msg", tooSmall
                    ? "Input should be greater than or equal to 1"
                    : "Input should be less than or equal to " + PCatalogFeedConfig.MAX_PAGE_SIZE);
            item.put("input", limit);
            item.put("ctx", java.util.Map.of(tooSmall ? "ge" : "le", tooSmall ? 1 : PCatalogFeedConfig.MAX_PAGE_SIZE));
            throw new ApiException(422, java.util.List.of(item));
        }
        String device = deviceId == null ? null : deviceId.trim();
        if (device != null && device.isEmpty()) {
            device = null;
        }
        String region = country == null ? null : country.trim();
        if (region != null && region.isEmpty()) {
            region = null;
        }
        if (region != null) {
            region = region.toUpperCase(Locale.ROOT);
        }
        Long userId = user == null ? null : user.getId();
        return feedService.buildFeed(userId, device, cursor, limit, region);
    }
}
