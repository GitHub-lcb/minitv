package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table dramas. Generated from the Python models. */
@Data
public class DramaRow {
    private Long id;
    private String title;
    private String synopsis;
    private String coverUrl;
    private Integer freeEpisodeCount;
    private Integer coinPricePerEpisode;
    private String status;
    private String countryAllowlist;
    private Integer ageRating;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime scheduledPublishAt;
    private Long playCount;
    private Long supplierId;
    private String reviewState;
    private String reviewNote;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;
}
