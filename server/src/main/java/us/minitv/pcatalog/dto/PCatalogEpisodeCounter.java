package us.minitv.pcatalog.dto;

import lombok.Data;

/** Published-episode counter for one drama (preview surface). */
@Data
public class PCatalogEpisodeCounter {
    private Long dramaId;
    /** count(*) over published episodes that have a video attached. */
    private Long episodeCount;
    /** count(*) of those whose episode_number <= dramas.free_episode_count. */
    private Long freePlayableCount;
}
