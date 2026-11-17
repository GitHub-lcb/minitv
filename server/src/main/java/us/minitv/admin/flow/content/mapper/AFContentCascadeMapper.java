package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Cross-table statements for drama impact/delete and its dependent rows.
 *
 * <p>The delete order mirrors {@code content/publish_service.delete_drama}: the
 * episode-scoped children first, then the drama-scoped children, then the drama
 * itself. Tables owned by other modules are written here on purpose so this
 * module never depends on another agent's mapper.</p>
 */
@Mapper
public interface AFContentCascadeMapper {

    List<String> listGroupNamesByDrama(@Param("dramaId") long dramaId);

    long countBannersByDrama(@Param("dramaId") long dramaId);

    long countPushRecordsByDrama(@Param("dramaId") long dramaId);

    long countEpisodesByDrama(@Param("dramaId") long dramaId);

    int nullCoinLedgerEpisodeIds(@Param("ids") List<Long> ids);

    int deleteEntitlementsByEpisodeIds(@Param("ids") List<Long> ids);

    int deleteMediaUploadsByEpisodeIds(@Param("ids") List<Long> ids);

    int deleteCdnPurgesByEpisodeIds(@Param("ids") List<Long> ids);

    int deletePlaybackEventsByEpisodeIds(@Param("ids") List<Long> ids);

    int deletePlaybackProgressByEpisodeIds(@Param("ids") List<Long> ids);

    int deleteCommentsByDrama(@Param("dramaId") long dramaId);

    int deleteEpisodesByDrama(@Param("dramaId") long dramaId);

    int deleteDramaTagsByDrama(@Param("dramaId") long dramaId);

    int deleteDramaHomeGroupsByDrama(@Param("dramaId") long dramaId);

    int deleteBannersByDrama(@Param("dramaId") long dramaId);

    int deletePushRecordsByDrama(@Param("dramaId") long dramaId);

    long countByStatus(@Param("status") String status);
}
