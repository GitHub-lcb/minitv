package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.content.row.EpisodeRow;

import java.time.LocalDateTime;
import java.util.List;

/** episodes statements used by the admin-core review publish path. */
@Mapper
public interface AdminCoreEpisodeMapper {

    @Select("SELECT COUNT(*) FROM episodes WHERE drama_id = #{dramaId}")
    long countByDrama(@Param("dramaId") long dramaId);

    @Select("SELECT * FROM episodes WHERE drama_id = #{dramaId} ORDER BY episode_number, id")
    List<EpisodeRow> listByDrama(@Param("dramaId") long dramaId);

    @Update("UPDATE episodes SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
