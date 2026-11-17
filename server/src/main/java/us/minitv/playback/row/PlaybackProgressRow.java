package us.minitv.playback.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table playback_progress. Generated from the Python models. */
@Data
public class PlaybackProgressRow {
    private Long userId;
    private Long episodeId;
    private Integer positionSeconds;
    private LocalDateTime updatedAt;
}
