package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.EpisodeRow;

import java.util.List;

/** Episode reads/writes used by the admin flow module (table {@code episodes}). */
@Mapper
public interface AFContentEpisodeMapper {

    EpisodeRow findById(@Param("id") long id);

    EpisodeRow findFirstByDramaOrdered(@Param("dramaId") long dramaId);

    List<EpisodeRow> listByDramaOrdered(@Param("dramaId") long dramaId);

    Long findDuplicateId(@Param("dramaId") long dramaId, @Param("number") int number);

    Long findDuplicateIdExcluding(@Param("dramaId") long dramaId, @Param("number") int number,
                                  @Param("id") long id);

    List<Integer> listNumbersByDrama(@Param("dramaId") long dramaId);

    long countPage(@Param("dramaId") long dramaId, @Param("status") String status,
                   @Param("q") String q, @Param("number") int number);

    List<EpisodeRow> page(@Param("dramaId") long dramaId, @Param("status") String status,
                          @Param("q") String q, @Param("number") int number,
                          @Param("limit") int limit, @Param("offset") int offset);

    List<EpisodeRow> listByIds(@Param("ids") List<Long> ids);

    List<EpisodeRow> listByStatusWithVideo(@Param("status") String status);

    List<Long> listIdsByDrama(@Param("dramaId") long dramaId);

    int insert(EpisodeRow row);

    /** Switch an episode between Bunny and an external source. */
    int updateSource(@Param("id") long id, @Param("provider") String provider,
                     @Param("videoId") String videoId, @Param("url") String url,
                     @Param("aspectRatio") String aspectRatio, @Param("status") String status,
                     @Param("bunnyVideoId") String bunnyVideoId);

    int updateTitle(@Param("id") long id, @Param("title") String title);

    int updateEpisodeNumber(@Param("id") long id, @Param("number") int number);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int updateDuration(@Param("id") long id, @Param("duration") int duration);

    int updateBunnyVideoId(@Param("id") long id, @Param("videoId") String videoId);

    int updateUploadFields(@Param("id") long id, @Param("videoId") String videoId,
                           @Param("mime") String mime, @Param("sizeBytes") long sizeBytes,
                           @Param("width") int width, @Param("height") int height,
                           @Param("duration") int duration);

    int updateSubtitleUrl(@Param("id") long id, @Param("subtitleUrl") String subtitleUrl);

    int updateSizeBytes(@Param("id") long id, @Param("sizeBytes") long sizeBytes);

    int deleteById(@Param("id") long id);
}
