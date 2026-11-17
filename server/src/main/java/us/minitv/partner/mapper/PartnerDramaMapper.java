package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** All drama queries on the supplier surface are scoped by {@code supplier_id}. */
@Mapper
public interface PartnerDramaMapper {

    DramaRow findById(@Param("id") long id);

    DramaRow findOwned(@Param("dramaId") long dramaId, @Param("supplierId") long supplierId);

    DramaRow findOwnedByTitle(@Param("supplierId") long supplierId, @Param("title") String title);

    DramaRow findOwnedByTitleExcluding(@Param("supplierId") long supplierId,
                                       @Param("title") String title,
                                       @Param("excludeId") long excludeId);

    List<DramaRow> listOwned(@Param("supplierId") long supplierId,
                             @Param("status") String status,
                             @Param("reviewState") String reviewState,
                             @Param("q") String q,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    /** Every drama of one supplier, oldest first (stats per-drama rows). */
    List<DramaRow> listBySupplier(@Param("supplierId") long supplierId);

    long countOwned(@Param("supplierId") long supplierId,
                    @Param("status") String status,
                    @Param("reviewState") String reviewState,
                    @Param("q") String q);

    List<Map<String, Object>> statusCounts(@Param("supplierId") long supplierId);

    List<Map<String, Object>> reviewCounts(@Param("supplierId") long supplierId);

    long countStage(@Param("supplierId") long supplierId,
                    @Param("statuses") List<String> statuses,
                    @Param("reviewStates") List<String> reviewStates);

    int insertDrama(DramaRow row);

    int updateDrama(@Param("row") DramaRow row,
                    @Param("ageRatingSet") boolean ageRatingSet,
                    @Param("reviewNoteSet") boolean reviewNoteSet);

    int updateCoverUrl(@Param("id") long id, @Param("coverUrl") String coverUrl);

    int updateSubmission(@Param("id") long id,
                         @Param("reviewState") String reviewState,
                         @Param("submittedAt") LocalDateTime submittedAt);

    int deleteDrama(@Param("id") long id);

    List<Long> tagIds(@Param("dramaId") long dramaId);

    int deleteDramaTags(@Param("dramaId") long dramaId);

    int insertDramaTag(@Param("dramaId") long dramaId, @Param("tagId") long tagId);
}
