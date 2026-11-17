package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.EpisodeRow;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounter;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounts;

import java.util.List;

/** Public episode reads; SQL lives in mapper/pcatalog_episode.xml. */
@Mapper
public interface PCatalogEpisodeMapper {

    /** Published episodes with a video attached, in episode order. */
    List<EpisodeRow> publishedPlayable(@Param("dramaId") long dramaId);

    /** All episodes of a drama in any status. */
    long countByDrama(@Param("dramaId") long dramaId);

    /** Per-drama counters over published, playable episodes of every drama. */
    List<PCatalogEpisodeCounter> publishedPlayableCounters();

    /** Published playable episodes for many dramas, grouped order by drama then number. */
    List<EpisodeRow> playableByDramaIds(@Param("ids") List<Long> ids);

    /** Total / published episode counts per drama (feed cards). */
    List<PCatalogEpisodeCounts> episodeCountsByDramaIds(@Param("ids") List<Long> ids);

    /** Published playable episodes by primary key (feed materialisation re-check). */
    List<EpisodeRow> playableByIds(@Param("ids") List<Long> ids);
}
