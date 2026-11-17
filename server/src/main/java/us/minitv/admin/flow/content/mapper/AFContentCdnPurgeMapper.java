package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.CdnPurgeRow;

/** Cache-invalidation rows (table {@code cdn_purges}). */
@Mapper
public interface AFContentCdnPurgeMapper {

    @Insert("INSERT INTO cdn_purges (episode_id, drama_id, reason) "
            + "VALUES (#{episodeId}, #{dramaId}, #{reason})")
    int insert(CdnPurgeRow row);

    @Delete("DELETE FROM cdn_purges WHERE episode_id = #{episodeId}")
    int deleteByEpisodeId(@Param("episodeId") long episodeId);
}
