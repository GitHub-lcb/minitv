package us.minitv.psocial;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.psocial.dto.PSocialCommentView;
import us.minitv.psocial.dto.PSocialCountRow;
import us.minitv.psocial.mapper.PSocialCommentMapper;
import us.minitv.psocial.mapper.PSocialContentMapper;
import us.minitv.psocial.mapper.PSocialReactionMapper;
import us.minitv.social.row.CommentRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Episode-scoped comments: the legacy drama-wide list, the paginated per-episode
 * thread, posting (with cooldown + daily limit + async moderation) and deletion.
 *
 * <p>Port of the comment block in {@code app/modules/public_flow.py}; response
 * key order and error texts are kept verbatim.</p>
 */
@Service
public class PSocialCommentService {

    public static final int COMMENT_PAGE_MAX = 50;
    public static final int COMMENT_MAX_LENGTH = 500;
    public static final int COMMENT_COOLDOWN_SECONDS = 30;
    public static final int COMMENT_DAILY_LIMIT = 50;

    private final PSocialCommentMapper comments;
    private final PSocialContentMapper content;
    private final PSocialReactionMapper reactions;
    private final PSocialModerationService moderation;

    public PSocialCommentService(PSocialCommentMapper comments,
                                 PSocialContentMapper content,
                                 PSocialReactionMapper reactions,
                                 PSocialModerationService moderation) {
        this.comments = comments;
        this.content = content;
        this.reactions = reactions;
        this.moderation = moderation;
    }

    /** GET /v1/dramas/{drama_id}/comments — legacy drama-wide approved list. */
    public Map<String, Object> listDramaComments(long dramaId) {
        List<CommentRow> rows = comments.listApprovedByDrama(dramaId);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (CommentRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("body", row.getBody());
            item.put("user_id", row.getUserId());
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        return response;
    }

    /** GET /v1/episodes/{episode_id}/comments — one thread, cursor paginated. */
    public Map<String, Object> listEpisodeComments(long episodeId, Integer limitParam, Long cursor, UserRow user) {
        EpisodeRow episode = content.findEpisodeById(episodeId);
        if (episode == null || !"published".equals(episode.getStatus())) {
            throw ApiException.notFound("episode not found");
        }

        int size = Math.max(1, Math.min(limitParam == null ? 20 : limitParam, COMMENT_PAGE_MAX));
        Long userId = user == null ? null : user.getId();
        // Python `if cursor:` — 0 is falsy and means "first page".
        Long cursorFilter = cursor == null || cursor == 0L ? null : cursor;

        List<PSocialCommentView> rows = new ArrayList<>(
                comments.listEpisodeComments(episodeId, userId, cursorFilter, size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, size));
        }
        // The cursor is the last row on this page, not the peeked extra one.
        Long nextCursor = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;

        List<Long> ids = new ArrayList<>(rows.size());
        for (PSocialCommentView row : rows) {
            ids.add(row.getId());
        }
        Map<Long, Long> likeCounts = new HashMap<>();
        Set<Long> liked = new HashSet<>();
        if (!ids.isEmpty()) {
            for (PSocialCountRow count : reactions.countByTarget("comment", "like", ids)) {
                likeCounts.put(count.getTargetId(), count.getTotal() == null ? 0L : count.getTotal());
            }
            if (user != null) {
                liked.addAll(reactions.listTargetIds(user.getId(), "comment", "like", ids));
            }
        }

        long total = commentTotal(episodeId, user);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (PSocialCommentView row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("body", row.getBody());
            item.put("created_at", row.getCreatedAt());
            item.put("like_count", likeCounts.getOrDefault(row.getId(), 0L));
            item.put("liked", liked.contains(row.getId()));
            item.put("mine", user != null && Objects.equals(row.getUserId(), user.getId()));
            item.put("user", commentUser(row.getPublicId(), row.getDisplayName(), row.getAvatarUrl()));
            item.put("pending", "pending".equals(row.getStatus()));
            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("next_cursor", nextCursor);
        response.put("total", total);
        return response;
    }

    /**
     * POST /v1/episodes/{episode_id}/comments (and the /v1/comments alias).
     *
     * <p>Returns the response payload plus the comment id the caller has to hand
     * to {@link PSocialModerationWorker#submitProcess(long)} once the transaction
     * has committed (when moderation is queued).</p>
     */
    @Transactional
    public CreateCommentResult createEpisodeComment(long episodeId, UserRow user, String rawBody) {
        EpisodeRow episode = content.findEpisodeById(episodeId);
        if (episode == null || !"published".equals(episode.getStatus())) {
            throw ApiException.notFound("episode not found");
        }
        DramaRow drama = content.findDramaById(episode.getDramaId());
        if (drama == null || !"published".equals(drama.getStatus())) {
            throw ApiException.notFound("episode not found");
        }

        String text = rawBody == null ? "" : rawBody.strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("comment is empty");
        }

        LocalDateTime now = Times.now();
        Long recent = comments.findRecentId(user.getId(), episodeId, now.minusSeconds(COMMENT_COOLDOWN_SECONDS));
        if (recent != null) {
            throw new ApiException(429, "You are commenting too fast. Try again in a moment.");
        }
        long daily = comments.countByUserSince(user.getId(), now.minusDays(1));
        if (daily >= COMMENT_DAILY_LIMIT) {
            throw new ApiException(429, "Daily comment limit reached.");
        }

        String queue = moderation.queueState();

        CommentRow row = new CommentRow();
        row.setUserId(user.getId());
        row.setDramaId(episode.getDramaId());
        row.setEpisodeId(episode.getId());
        row.setBody(text);
        row.setStatus("pending");
        row.setModerationState(queue);
        row.setModerationAttempts(0);
        comments.insert(row);

        // Python refreshes the row to pick up the server-side created_at.
        CommentRow stored = comments.findById(row.getId());
        if (stored == null) {
            stored = row;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", stored.getId());
        payload.put("body", stored.getBody());
        payload.put("created_at", stored.getCreatedAt());
        payload.put("like_count", 0);
        payload.put("liked", false);
        payload.put("mine", true);
        payload.put("user", commentUser(user));
        payload.put("pending", true);

        long total = commentTotal(episodeId, user);

        Map<String, Object> moderationBlock = new LinkedHashMap<>();
        moderationBlock.put("state", queue == null ? "disabled" : queue);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", stored.getId());
        response.put("status", "pending");
        response.put("item", payload);
        response.put("total", total);
        response.put("moderation", moderationBlock);

        return new CreateCommentResult(response, queue == null ? null : stored.getId());
    }

    /** DELETE /v1/comments/{comment_id}. */
    @Transactional
    public Map<String, Object> deleteComment(long commentId, UserRow user) {
        CommentRow row = comments.findById(commentId);
        if (row == null) {
            throw ApiException.notFound("comment not found");
        }
        if (!Objects.equals(row.getUserId(), user.getId())) {
            throw new ApiException(403, "not your comment");
        }
        if ("deleted".equals(row.getStatus())) {
            return deletedResponse();
        }
        if (row.getCreatedAt() != null && row.getCreatedAt().isBefore(Times.now().minusHours(24))) {
            throw new ApiException(400, "Comments can only be deleted within 24 hours.");
        }
        comments.updateStatus(commentId, "deleted");
        return deletedResponse();
    }

    private Map<String, Object> deletedResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "deleted");
        return response;
    }

    private long commentTotal(long episodeId, UserRow user) {
        long total = comments.countApprovedByEpisode(episodeId);
        if (user != null) {
            total += comments.countPendingByEpisodeAndUser(episodeId, user.getId());
        }
        return total;
    }

    private Map<String, Object> commentUser(UserRow user) {
        if (user == null) {
            return commentUser("", null, null);
        }
        return commentUser(user.getPublicId(), user.getDisplayName(), user.getAvatarUrl());
    }

    private Map<String, Object> commentUser(String publicId, String displayName, String avatarUrl) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("public_id", emptyIfNull(publicId));
        user.put("display_name", displayName == null || displayName.isEmpty() ? "MiniTV user" : displayName);
        user.put("avatar_url", emptyIfNull(avatarUrl));
        return user;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    /** Response payload + the comment id to enqueue for background moderation. */
    public record CreateCommentResult(Map<String, Object> response, Long enqueuedCommentId) {
    }
}
