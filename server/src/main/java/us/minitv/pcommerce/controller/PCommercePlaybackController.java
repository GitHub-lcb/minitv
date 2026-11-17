package us.minitv.pcommerce.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.pcommerce.service.PCommercePlaybackService;
import us.minitv.pcommerce.support.PCommerceRequests;
import us.minitv.security.CurrentUser;

import java.util.LinkedHashMap;
import java.util.Map;

/** Playback authorization, progress events and the drama play page. */
@RestController
public class PCommercePlaybackController {

    private final PCommercePlaybackService playback;

    public PCommercePlaybackController(PCommercePlaybackService playback) {
        this.playback = playback;
    }

    public record PlaybackIn(@JsonProperty("drama_id") Long dramaId,
                             @JsonProperty("episode_id") Long episodeId,
                             @JsonProperty("country") String country) {
    }

    public record EventIn(@JsonProperty("episode_id") Long episodeId,
                          @JsonProperty("position_seconds") Integer positionSeconds) {
    }

    /** POST /v1/playback */
    @PostMapping("/v1/playback")
    public Map<String, Object> authorize(@RequestBody PlaybackIn body,
                                        @CurrentUser(required = false) UserRow user) {
        long dramaId = PCommerceRequests.required(body.dramaId(), "drama_id");
        long episodeId = PCommerceRequests.required(body.episodeId(), "episode_id");
        return playback.authorize(dramaId, episodeId, user == null ? null : user.getId(), body.country());
    }

    /** POST /v1/playback/start */
    @PostMapping("/v1/playback/start")
    public Map<String, Object> start(@RequestBody EventIn body,
                                     @CurrentUser(required = false) UserRow user) {
        record(body, "start", user);
        return ok();
    }

    /** POST /v1/playback/progress */
    @PostMapping("/v1/playback/progress")
    public Map<String, Object> progress(@RequestBody EventIn body,
                                        @CurrentUser(required = false) UserRow user) {
        record(body, "progress", user);
        return ok();
    }

    /** POST /v1/playback/complete */
    @PostMapping("/v1/playback/complete")
    public Map<String, Object> complete(@RequestBody EventIn body,
                                        @CurrentUser(required = false) UserRow user) {
        record(body, "complete", user);
        return ok();
    }

    /** GET /v1/dramas/{drama_id}/play */
    @GetMapping("/v1/dramas/{drama_id}/play")
    public Map<String, Object> dramaPlay(@PathVariable("drama_id") long dramaId,
                                         @CurrentUser(required = false) UserRow user) {
        return playback.dramaPlay(dramaId, user == null ? null : user.getId());
    }

    private void record(EventIn body, String kind, UserRow user) {
        long episodeId = PCommerceRequests.required(body.episodeId(), "episode_id");
        int position = body.positionSeconds() == null ? 0 : body.positionSeconds();
        playback.recordEvent(episodeId, kind, position, user == null ? null : user.getId());
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }
}
