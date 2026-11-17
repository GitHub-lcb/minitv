package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.MediaUploadRow;

import java.util.List;
import java.util.Map;

/** Upload-session rows (one per Bunny video object). */
@Mapper
public interface PartnerMediaUploadMapper {

    MediaUploadRow findByVideoId(@Param("videoId") String videoId);

    MediaUploadRow findOwnedByVideoId(@Param("videoId") String videoId, @Param("supplierId") long supplierId);

    MediaUploadRow findLatestByEpisode(@Param("episodeId") long episodeId);

    MediaUploadRow findLatestByEpisodeAndVideo(@Param("episodeId") long episodeId,
                                               @Param("videoId") String videoId);

    /** {@code [{episodeId, status}]} ordered by upload id, so "last wins" per episode. */
    List<Map<String, Object>> statusesByEpisodes(@Param("episodeIds") List<Long> episodeIds);

    int insertUpload(MediaUploadRow row);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int updateStatusAndSize(@Param("id") long id,
                            @Param("status") String status,
                            @Param("sizeBytes") long sizeBytes);
}
