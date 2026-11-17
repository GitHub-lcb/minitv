package us.minitv.social.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table comments. Generated from the Python models. */
@Data
public class CommentRow {
    private Long id;
    private Long userId;
    private Long dramaId;
    private Long episodeId;
    private String body;
    private String status;
    private String moderationVerdict;
    private Double moderationScore;
    private String moderationReason;
    private String moderationModel;
    private LocalDateTime moderatedAt;
    private String moderationState;
    private Integer moderationAttempts;
    private LocalDateTime moderationClaimedAt;
    private String moderationError;
    private LocalDateTime createdAt;
}
