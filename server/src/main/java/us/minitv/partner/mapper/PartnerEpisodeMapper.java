package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.EpisodeRow;

import java.util.List;

/** Episode queries scoped to one supplier through their parent drama. */
@Mapper
public interface PartnerEpisodeMapper {

    EpisodeRow findById(@Param("id") long id);

    EpisodeRow findOwned(@Param("episodeId") long episodeId, @Param("supplierId") long supplierId);

    long countByDrama(@Param("dramaId") long dramaId);

    Integer maxEpisodeNumber(@Param("dramaId") long dramaId);

    Long findIdByDramaNumber(@Param("dramaId") long dramaId, @Param("episodeNumber") int episodeNumber);

    Long findOtherIdByDramaNumber(@Param("dramaId") long dramaId,
                                  @Param("episodeNumber") int episodeNumber,
                                  @Param("excludeId") long excludeId);

    List<EpisodeRow> listByDrama(@Param("dramaId") long dramaId);

    int insertEpisode(EpisodeRow row);

    int updateEpisode(EpisodeRow row);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    /** Reset for a video replacement: in_review/published/unpublished -> not_uploaded. */
    int resetForReplace(@Param("id") long id);

    int updateUploadFields(@Param("id") long id,
                           @Param("bunnyVideoId") String bunnyVideoId,
                           @Param("mime") String mime,
                           @Param("sizeBytes") long sizeBytes,
                           @Param("width") int width,
                           @Param("height") int height,
                           @Param("durationSeconds") int durationSeconds,
                           @Param("status") String status);

    int updateSizeBytes(@Param("id") long id, @Param("sizeBytes") long sizeBytes);

    int deleteEpisode(@Param("id") long id);
}
