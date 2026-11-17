package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.playback.row.PlaybackEventRow;

import java.util.List;

/** playback_events / playback_progress plus the play_count counter they feed. */
@Mapper
public interface PCommercePlaybackMapper {

    @Insert("INSERT INTO playback_events (user_id, episode_id, kind, position_seconds) "
            + "VALUES (#{userId,jdbcType=BIGINT}, #{episodeId}, #{kind}, #{positionSeconds})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(PlaybackEventRow row);

    @Select("SELECT drama_id FROM episodes WHERE id = #{episodeId}")
    Long selectEpisodeDramaId(@Param("episodeId") long episodeId);

    @Update("UPDATE dramas SET play_count = play_count + 1, updated_at = now() WHERE id = #{dramaId}")
    int incrementPlayCount(@Param("dramaId") long dramaId);

    @Insert("INSERT INTO playback_progress (user_id, episode_id, position_seconds, updated_at) "
            + "VALUES (#{userId}, #{episodeId}, #{positionSeconds}, now()) "
            + "ON DUPLICATE KEY UPDATE position_seconds = VALUES(position_seconds), updated_at = now()")
    int upsertProgress(@Param("userId") long userId,
                       @Param("episodeId") long episodeId,
                       @Param("positionSeconds") int positionSeconds);

    @Select("SELECT episode_id, position_seconds FROM playback_progress WHERE user_id = #{userId}")
    List<us.minitv.pcommerce.dto.PCommerceEpisodePosition> progressByUser(@Param("userId") long userId);

    @Select("SELECT episode_id FROM playback_events WHERE user_id = #{userId} AND kind = 'complete'")
    List<Long> completedEpisodeIds(@Param("userId") long userId);
}
