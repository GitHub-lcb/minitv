package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.MediaUploadRow;

/** Upload-attempt rows (table {@code media_uploads}). */
@Mapper
public interface AFContentMediaUploadMapper {

    MediaUploadRow findByVideoId(@Param("videoId") String videoId);

    /** Python {@code _latest_media_upload}: newest row for the episode, optionally by video id. */
    MediaUploadRow findLatestByEpisode(@Param("episodeId") long episodeId, @Param("videoId") String videoId);

    int insert(MediaUploadRow row);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int updateStatusAndSize(@Param("id") long id, @Param("status") String status,
                            @Param("sizeBytes") long sizeBytes);

    int deleteByEpisodeId(@Param("episodeId") long episodeId);
}
