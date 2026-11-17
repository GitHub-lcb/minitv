package us.minitv.pcommerce.dto;

import lombok.Data;

/** episode_id -> position_seconds pair from playback_progress. */
@Data
public class PCommerceEpisodePosition {
    private Long episodeId;
    private Integer positionSeconds;
}
