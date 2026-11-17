package us.minitv.playback.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table playback_events. Generated from the Python models. */
@Data
public class PlaybackEventRow {
    private Long id;
    private Long userId;
    private Long episodeId;
    private String kind;
    private Integer positionSeconds;
    private LocalDateTime createdAt;
}
