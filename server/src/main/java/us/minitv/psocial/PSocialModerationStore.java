package us.minitv.psocial;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.Times;
import us.minitv.ops.row.ModerationSettingRow;
import us.minitv.psocial.mapper.PSocialCommentMapper;
import us.minitv.psocial.mapper.PSocialModerationMapper;
import us.minitv.social.row.CommentRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional units of the moderation flow.
 *
 * <p>Kept in its own bean so the {@code @Transactional} proxy is actually
 * applied: {@link PSocialModerationService} coordinates several short
 * transactions around the (slow) model call, exactly like the Python worker
 * opened and closed its own sessions.</p>
 */
@Service
public class PSocialModerationStore {

    private final PSocialCommentMapper comments;
    private final PSocialModerationMapper settingsMapper;

    public PSocialModerationStore(PSocialCommentMapper comments, PSocialModerationMapper settingsMapper) {
        this.comments = comments;
        this.settingsMapper = settingsMapper;
    }

    @Transactional(readOnly = true)
    public ModerationSettingRow findSettings() {
        return settingsMapper.findSettings();
    }

    /** Python's {@code get_settings()}: seed the singleton row on first read. */
    @Transactional
    public ModerationSettingRow getOrCreateSettings(ModerationSettingRow defaults) {
        ModerationSettingRow row = settingsMapper.findSettings();
        if (row != null) {
            return row;
        }
        settingsMapper.insertSettings(defaults);
        return settingsMapper.findSettings();
    }

    /**
     * Claim one comment for the background worker. Returns {@code null} when the
     * row is not claimable, mirroring {@code UPDATE ... RETURNING} finding no row.
     */
    @Transactional
    public Claim claim(long commentId, LocalDateTime staleBefore) {
        CommentRow row = comments.findForUpdate(commentId);
        if (row == null) {
            return null;
        }
        boolean queued = "queued".equals(row.getModerationState());
        boolean staleRunning = "running".equals(row.getModerationState())
                && row.getModerationClaimedAt() != null
                && row.getModerationClaimedAt().isBefore(staleBefore);
        if (!queued && !staleRunning) {
            return null;
        }
        comments.markModerationRunning(commentId, Times.now());
        int attempts = (row.getModerationAttempts() == null ? 0 : row.getModerationAttempts()) + 1;
        return new Claim(row.getBody(), attempts);
    }

    @Transactional
    public void cancel(long commentId) {
        comments.markModerationCanceled(commentId);
    }

    @Transactional
    public void recordFailure(long commentId, String state, String error) {
        comments.markModerationFailure(commentId, state, error);
    }

    @Transactional
    public void recordSuccess(long commentId, PSocialModerationService.ModerationResult result, String status) {
        comments.markModerationSuccess(commentId, result.verdict(), result.score(), result.reason(),
                result.model(), Times.now(), status);
    }

    @Transactional(readOnly = true)
    public List<Long> findUnprocessedIds(LocalDateTime staleBefore, int limit) {
        return comments.findUnprocessedIds(staleBefore, limit);
    }

    /** Body + attempts of a successfully claimed comment. */
    public record Claim(String body, int attempts) {
    }
}
