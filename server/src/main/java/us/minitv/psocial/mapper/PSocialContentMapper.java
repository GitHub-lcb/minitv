package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.psocial.dto.PSocialDramaCount;

import java.util.List;

/**
 * Read-only lookups into the content tables this module needs (episode/drama
 * gating for comments, published-episode counts on cards). Duplicated here on
 * purpose: the content module's mappers are owned by another agent.
 */
@Mapper
public interface PSocialContentMapper {

    EpisodeRow findEpisodeById(@Param("id") long id);

    DramaRow findDramaById(@Param("id") long id);

    List<DramaRow> listPublishedDramasByIds(@Param("ids") List<Long> ids);

    List<PSocialDramaCount> listPublishedEpisodeCounts(@Param("ids") List<Long> ids);
}
