package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.pcommerce.dto.PCommerceEpisodeCount;

import java.util.List;

/** Read-only comment/reaction aggregates for the drama play page. */
@Mapper
public interface PCommerceSocialMapper {

    List<PCommerceEpisodeCount> approvedCommentCounts(@Param("episodeIds") List<Long> episodeIds);

    List<PCommerceEpisodeCount> pendingCommentCounts(@Param("episodeIds") List<Long> episodeIds,
                                                     @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM comments WHERE drama_id = #{dramaId} AND status = 'approved'")
    long countDramaComments(@Param("dramaId") long dramaId);

    @Select("SELECT COUNT(*) FROM reactions WHERE target_type = #{targetType} "
            + "AND target_id = #{targetId} AND kind = #{kind}")
    long countReactions(@Param("targetType") String targetType,
                        @Param("targetId") long targetId,
                        @Param("kind") String kind);

    @Select("SELECT kind FROM reactions WHERE user_id = #{userId} AND target_type = #{targetType} "
            + "AND target_id = #{targetId}")
    List<String> userReactionKinds(@Param("userId") long userId,
                                   @Param("targetType") String targetType,
                                   @Param("targetId") long targetId);
}
