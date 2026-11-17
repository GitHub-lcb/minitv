package us.minitv.social.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table inbox_messages. Generated from the Python models. */
@Data
public class InboxMessageRow {
    private Long id;
    private Long userId;
    private String kind;
    private String title;
    private String body;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
