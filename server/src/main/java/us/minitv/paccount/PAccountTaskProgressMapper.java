package us.minitv.paccount;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * Daily task progress: distinct objects the user touched inside one Los Angeles
 * calendar day (account/tasks.py task_progress). The LA day is converted to UTC
 * window bounds in Java, so no MySQL timezone function is needed.
 */
@Mapper
public interface PAccountTaskProgressMapper {

    @Select("SELECT COUNT(DISTINCT episode_id) FROM playback_events "
            + "WHERE user_id = #{userId} AND kind = 'complete' AND position_seconds > 0 "
            + "AND created_at >= #{start} AND created_at < #{end}")
    int countCompletedEpisodes(@Param("userId") long userId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(DISTINCT target_id) FROM reactions "
            + "WHERE user_id = #{userId} AND kind = #{kind} AND target_type = 'drama' "
            + "AND created_at >= #{start} AND created_at < #{end}")
    int countDramaReactions(@Param("userId") long userId,
                            @Param("kind") String kind,
                            @Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end);
}
