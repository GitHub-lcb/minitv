package us.minitv.psocial.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One watch-history row: playback_progress joined to its episode and drama as
 * selected by {@code psocial_history.xml}.
 */
@Data
public class PSocialHistoryView {
    private Long episodeId;
    private Integer positionSeconds;
    private Integer episodeNumber;
    private Integer durationSeconds;
    private LocalDateTime updatedAt;
    private Long dramaId;
    private String title;
    private String coverUrl;
    private String synopsis;
    private Long playCount;
}
