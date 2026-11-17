package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.content.row.MediaUploadRow;

/** media_uploads plus the episode columns the Bunny callback updates. */
@Mapper
public interface PCommerceUploadMapper {

    @Select("SELECT * FROM media_uploads WHERE video_id = #{videoId} LIMIT 1")
    MediaUploadRow findByVideoId(@Param("videoId") String videoId);

    @Update("UPDATE media_uploads SET status = #{status} WHERE id = #{id}")
    int updateUploadStatus(@Param("id") long id, @Param("status") String status);

    @Update("UPDATE episodes SET status = #{status}, updated_at = now() WHERE id = #{id}")
    int updateEpisodeStatus(@Param("id") long id, @Param("status") String status);

    @Update("UPDATE episodes SET duration_seconds = #{durationSeconds}, updated_at = now() WHERE id = #{id}")
    int updateEpisodeDuration(@Param("id") long id, @Param("durationSeconds") int durationSeconds);
}
