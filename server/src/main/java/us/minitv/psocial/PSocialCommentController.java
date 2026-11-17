package us.minitv.psocial;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.psocial.dto.PSocialCommentIn;
import us.minitv.psocial.dto.PSocialEpisodeCommentIn;
import us.minitv.psocial.dto.PSocialReactionIn;
import us.minitv.security.CurrentUser;

import java.util.Map;

/**
 * Comments and reactions (public-social module).
 *
 * <p>Endpoints: the legacy drama-wide comment list, the per-episode thread
 * (list + create), the legacy create alias, delete and reaction toggling.</p>
 */
@RestController
public class PSocialCommentController {

    private final PSocialCommentService comments;
    private final PSocialReactionService reactions;
    private final PSocialModerationWorker moderationWorker;

    public PSocialCommentController(PSocialCommentService comments,
                                    PSocialReactionService reactions,
                                    PSocialModerationWorker moderationWorker) {
        this.comments = comments;
        this.reactions = reactions;
        this.moderationWorker = moderationWorker;
    }

    /** Legacy drama-wide list. Kept for older clients; new UI reads per episode. */
    @GetMapping("/v1/dramas/{drama_id}/comments")
    public Map<String, Object> listDramaComments(@PathVariable("drama_id") long dramaId) {
        return comments.listDramaComments(dramaId);
    }

    /** One episode, one thread. Guests see approved rows only. */
    @GetMapping("/v1/episodes/{episode_id}/comments")
    public Map<String, Object> listEpisodeComments(
            @PathVariable("episode_id") long episodeId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @CurrentUser(required = false) UserRow user) {
        return comments.listEpisodeComments(episodeId, limit, cursor, user);
    }

    /** Post into one episode's thread and return immediately (visibility starts pending). */
    @PostMapping("/v1/episodes/{episode_id}/comments")
    public Map<String, Object> createEpisodeComment(
            @PathVariable("episode_id") long episodeId,
            @CurrentUser UserRow user,
            @Valid @RequestBody PSocialEpisodeCommentIn body) {
        PSocialCommentService.CreateCommentResult result =
                comments.createEpisodeComment(episodeId, user, body.body());
        enqueue(result);
        return result.response();
    }

    /** Backward-compatible alias: episode is now mandatory. */
    @PostMapping("/v1/comments")
    public Map<String, Object> createComment(
            @CurrentUser UserRow user,
            @Valid @RequestBody PSocialCommentIn body) {
        if (body.episodeId() == null) {
            throw ApiException.unprocessable("episode_id is required");
        }
        if (body.body() != null && body.body().length() > PSocialCommentService.COMMENT_MAX_LENGTH) {
            // Python constructs EpisodeCommentIn inside the handler here, so a
            // 501..1000 character body escapes FastAPI's 422 layer and becomes a
            // 500. Reproduced instead of "fixed".
            throw new IllegalStateException("EpisodeCommentIn validation failed");
        }
        PSocialCommentService.CreateCommentResult result =
                comments.createEpisodeComment(body.episodeId(), user, body.body());
        enqueue(result);
        return result.response();
    }

    @DeleteMapping("/v1/comments/{comment_id}")
    public Map<String, Object> deleteComment(
            @PathVariable("comment_id") long commentId,
            @CurrentUser UserRow user) {
        return comments.deleteComment(commentId, user);
    }

    @PostMapping("/v1/reactions")
    public Map<String, Object> react(@CurrentUser UserRow user, @Valid @RequestBody PSocialReactionIn body) {
        return reactions.react(user, body);
    }

    private void enqueue(PSocialCommentService.CreateCommentResult result) {
        if (result.enqueuedCommentId() != null) {
            moderationWorker.submitProcess(result.enqueuedCommentId());
        }
    }
}
