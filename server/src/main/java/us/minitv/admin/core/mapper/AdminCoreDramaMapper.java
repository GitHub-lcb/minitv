package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.content.row.DramaRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** dramas statements used by the admin-core surfaces (ops.router / review_router). */
@Mapper
public interface AdminCoreDramaMapper {

    @Select("SELECT * FROM dramas WHERE id = #{id}")
    DramaRow findById(@Param("id") long id);

    /** Mirror of the post-IntegrityError lookup in ops.router.create_drama. */
    @Select("SELECT * FROM dramas WHERE title = #{title} ORDER BY id LIMIT 1")
    DramaRow findByTitleFirst(@Param("title") String title);

    /** Filters mirror ops.router.list_dramas; ordering is updated_at DESC with an id tie-break. */
    List<DramaRow> list(@Param("status") String status,
                        @Param("q") String q,
                        @Param("tagId") Long tagId,
                        @Param("limit") int limit,
                        @Param("offset") int offset);

    long count(@Param("status") String status,
               @Param("q") String q,
               @Param("tagId") Long tagId);

    @Select("SELECT status, COUNT(*) AS n FROM dramas GROUP BY status")
    List<Map<String, Object>> statusCounts();

    @Insert("""
            INSERT INTO dramas (title, synopsis, cover_url, free_episode_count, coin_price_per_episode,
                                status, country_allowlist, play_count, review_state, created_at, updated_at)
            VALUES (#{title}, #{synopsis}, #{coverUrl}, #{freeEpisodeCount}, #{coinPricePerEpisode},
                    #{status}, #{countryAllowlist}, #{playCount}, #{reviewState}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DramaRow row);

    int updateFields(@Param("id") long id,
                     @Param("setTitle") boolean setTitle,
                     @Param("title") String title,
                     @Param("setSynopsis") boolean setSynopsis,
                     @Param("synopsis") String synopsis,
                     @Param("setCoverUrl") boolean setCoverUrl,
                     @Param("coverUrl") String coverUrl,
                     @Param("setFreeEpisodeCount") boolean setFreeEpisodeCount,
                     @Param("freeEpisodeCount") Integer freeEpisodeCount,
                     @Param("setCoinPricePerEpisode") boolean setCoinPricePerEpisode,
                     @Param("coinPricePerEpisode") Integer coinPricePerEpisode,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE dramas SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE dramas SET status = #{status}, published_at = #{publishedAt}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatusAndPublished(@Param("id") long id,
                                 @Param("status") String status,
                                 @Param("publishedAt") LocalDateTime publishedAt,
                                 @Param("updatedAt") LocalDateTime updatedAt);
}
