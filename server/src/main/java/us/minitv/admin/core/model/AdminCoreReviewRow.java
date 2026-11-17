package us.minitv.admin.core.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Review queue row: dramas joined with their supplier and episode count. */
@Data
public class AdminCoreReviewRow {
    private Long id;
    private String title;
    private String coverUrl;
    private String status;
    private String reviewState;
    private String reviewNote;
    private Integer freeEpisodeCount;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private Long supplierId;
    private String supplierName;
    private String supplierContactEmail;
    private Long episodeCount;
}
