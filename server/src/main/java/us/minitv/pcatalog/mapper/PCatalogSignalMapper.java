package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.billing.row.SubscriptionRow;
import us.minitv.pcatalog.dto.PCatalogAffinityEvent;
import us.minitv.pcatalog.dto.PCatalogAffinityReaction;
import us.minitv.pcatalog.dto.PCatalogCommentCount;
import us.minitv.pcatalog.dto.PCatalogDramaAggregate;
import us.minitv.pcatalog.dto.PCatalogReactionTotal;
import us.minitv.playback.row.PlaybackProgressRow;

import java.time.LocalDateTime;
import java.util.List;

/** Cross-module tables the For-You feed reads; SQL lives in mapper/pcatalog_signal.xml. */
@Mapper
public interface PCatalogSignalMapper {

    List<PCatalogDramaAggregate> dramaAggregates(@Param("ids") List<Long> ids,
                                                 @Param("longWindow") LocalDateTime longWindow,
                                                 @Param("shortWindow") LocalDateTime shortWindow);

    long countEvents(@Param("kind") String kind, @Param("since") LocalDateTime since);

    List<PlaybackProgressRow> progressForUser(@Param("userId") long userId);

    List<Long> completedEpisodeIds(@Param("userId") long userId);

    List<Long> unlockedEpisodeIds(@Param("userId") long userId);

    List<PCatalogAffinityEvent> affinityEvents(@Param("userId") long userId,
                                               @Param("cutoff") LocalDateTime cutoff);

    List<PCatalogAffinityReaction> affinityReactions(@Param("userId") long userId,
                                                     @Param("cutoff") LocalDateTime cutoff);

    List<PCatalogReactionTotal> likeTotals(@Param("ids") List<Long> ids);

    List<Long> likedDramaIds(@Param("userId") long userId, @Param("ids") List<Long> ids);

    List<PCatalogCommentCount> approvedCommentCounts(@Param("episodeIds") List<Long> ids);

    List<PCatalogCommentCount> pendingCommentCounts(@Param("episodeIds") List<Long> ids,
                                                    @Param("userId") long userId);

    /** At most two rows so a broken "one active subscription" invariant can be detected. */
    List<SubscriptionRow> activeSubscriptions(@Param("userId") long userId);
}
