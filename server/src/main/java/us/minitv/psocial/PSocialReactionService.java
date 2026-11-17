package us.minitv.psocial;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.psocial.dto.PSocialReactionIn;
import us.minitv.psocial.mapper.PSocialCommentMapper;
import us.minitv.psocial.mapper.PSocialReactionMapper;
import us.minitv.social.row.CommentRow;
import us.minitv.social.row.ReactionRow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * POST /v1/reactions: idempotent toggle for like/favorite/follow rows.
 *
 * <p>Port of {@code react()} in {@code public_flow.py}. Comment reactions are
 * only accepted for approved comments.</p>
 */
@Service
public class PSocialReactionService {

    private static final Set<String> TARGET_TYPES = Set.of("drama", "episode", "comment");

    private final PSocialReactionMapper reactions;
    private final PSocialCommentMapper comments;

    public PSocialReactionService(PSocialReactionMapper reactions, PSocialCommentMapper comments) {
        this.reactions = reactions;
        this.comments = comments;
    }

    @Transactional
    public Map<String, Object> react(UserRow user, PSocialReactionIn body) {
        String targetType = body.targetType();
        if (!TARGET_TYPES.contains(targetType)) {
            throw ApiException.unprocessable("bad target_type");
        }
        if ("comment".equals(targetType)) {
            CommentRow target = comments.findById(body.targetId());
            if (target == null || !"approved".equals(target.getStatus())) {
                throw ApiException.notFound("comment not found");
            }
        }

        ReactionRow existing = reactions.findOne(user.getId(), targetType, body.targetId(), body.kind());

        Map<String, Object> response = new LinkedHashMap<>();
        if (body.removeFlag()) {
            if (existing != null) {
                reactions.deleteById(existing.getId());
            }
            response.put("active", Boolean.FALSE);
            return response;
        }
        if (existing == null) {
            ReactionRow row = new ReactionRow();
            row.setUserId(user.getId());
            row.setTargetType(targetType);
            row.setTargetId(body.targetId());
            row.setKind(body.kind());
            reactions.insert(row);
        }
        response.put("active", Boolean.TRUE);
        return response;
    }
}
