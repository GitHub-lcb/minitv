package us.minitv.pcommerce.dto;

import lombok.Data;

/** episode_id -> COUNT(*) pair used by the drama play page. */
@Data
public class PCommerceEpisodeCount {
    private Long episodeId;
    private Long total;
}
