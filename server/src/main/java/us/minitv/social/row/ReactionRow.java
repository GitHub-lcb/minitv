package us.minitv.social.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table reactions. Generated from the Python models. */
@Data
public class ReactionRow {
    private Long id;
    private Long userId;
    private String targetType;
    private Long targetId;
    private String kind;
    private LocalDateTime createdAt;
}
