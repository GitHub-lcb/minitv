package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.psocial.dto.PSocialCommentView;
import us.minitv.social.row.CommentRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * comments table, plus the comment-moderation state machine updates
 * (claim / running / done / failed / canceled). SQL lives in
 * {@code mapper/psocial_comment.xml}.
 */
@Mapper
public interface PSocialCommentMapper {

    CommentRow findById(@Param("id") long id);

    /** Row lock used by the moderation queue claim (replaces UPDATE .. RETURNING). */
    CommentRow findForUpdate(@Param("id") long id);

    List<CommentRow> listApprovedByDrama(@Param("dramaId") long dramaId);

    List<PSocialCommentView> listEpisodeComments(@Param("episodeId") long episodeId,
                                                 @Param("userId") Long userId,
                                                 @Param("cursor") Long cursor,
                                                 @Param("limit") int limit);

    long countApprovedByEpisode(@Param("episodeId") long episodeId);

    long countPendingByEpisodeAndUser(@Param("episodeId") long episodeId,
                                      @Param("userId") long userId);

    Long findRecentId(@Param("userId") long userId,
                      @Param("episodeId") long episodeId,
                      @Param("since") LocalDateTime since);

    long countByUserSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    int insert(CommentRow row);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int markModerationRunning(@Param("id") long id, @Param("now") LocalDateTime now);

    int markModerationCanceled(@Param("id") long id);

    int markModerationFailure(@Param("id") long id,
                              @Param("state") String state,
                              @Param("error") String error);

    int markModerationSuccess(@Param("id") long id,
                              @Param("verdict") String verdict,
                              @Param("score") Double score,
                              @Param("reason") String reason,
                              @Param("model") String model,
                              @Param("moderatedAt") LocalDateTime moderatedAt,
                              @Param("status") String status);

    List<Long> findUnprocessedIds(@Param("staleBefore") LocalDateTime staleBefore,
                                  @Param("limit") int limit);
}
