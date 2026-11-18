package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table episodes. Generated from the Python models. */
@Data
public class EpisodeRow {
    private Long id;
    private Long dramaId;
    private Integer episodeNumber;
    private String title;
    private Integer durationSeconds;
    private String bunnyVideoId;
    private String videoProvider;
    private String externalVideoId;
    private String externalUrl;
    private String aspectRatio;
    private String subtitleUrl;
    private Integer width;
    private Integer height;
    private Long sizeBytes;
    private String mime;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
