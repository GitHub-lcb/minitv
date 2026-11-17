package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.admin.core.model.AdminCoreReviewRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Supplier review queue statements (ops/review_router.py). */
@Mapper
public interface AdminCoreReviewMapper {

    @Select("SELECT review_state, COUNT(*) AS n FROM dramas GROUP BY review_state")
    List<Map<String, Object>> reviewStateCounts();

    @Select("SELECT COUNT(*) FROM dramas WHERE review_state = 'approved' AND status IN ('draft', 'in_review')")
    long countPendingPublish();

    List<AdminCoreReviewRow> listReviews(@Param("state") String state,
                                         @Param("onlyUnpublished") boolean onlyUnpublished,
                                         @Param("q") String q,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    long countReviews(@Param("state") String state,
                      @Param("onlyUnpublished") boolean onlyUnpublished,
                      @Param("q") String q);

    /** Conditional UPDATE: two operators cannot double-review the same drama. */
    @Update("""
            UPDATE dramas
            SET review_state = #{reviewState},
                review_note = #{reviewNote},
                reviewed_at = #{reviewedAt},
                reviewed_by = #{reviewedBy},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND review_state = 'pending'
            """)
    int applyDecision(@Param("id") long id,
                      @Param("reviewState") String reviewState,
                      @Param("reviewNote") String reviewNote,
                      @Param("reviewedAt") LocalDateTime reviewedAt,
                      @Param("reviewedBy") long reviewedBy,
                      @Param("updatedAt") LocalDateTime updatedAt);
}
