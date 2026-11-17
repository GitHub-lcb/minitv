package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;

import java.util.List;

/** The public visibility reads this module needs from the content tables. */
@Mapper
public interface PCommerceContentMapper {

    @Select("SELECT * FROM dramas WHERE id = #{id}")
    DramaRow findDrama(@Param("id") long id);

    @Select("SELECT * FROM episodes WHERE id = #{id}")
    EpisodeRow findEpisode(@Param("id") long id);

    @Select("SELECT * FROM episodes WHERE drama_id = #{dramaId} AND status = 'published' "
            + "ORDER BY episode_number")
    List<EpisodeRow> listPublishedEpisodes(@Param("dramaId") long dramaId);

    @Select("SELECT t.* FROM tags t JOIN drama_tags dt ON dt.tag_id = t.id "
            + "WHERE dt.drama_id = #{dramaId} AND t.status = 'enabled' ORDER BY t.id")
    List<TagRow> tagsForDrama(@Param("dramaId") long dramaId);
}
