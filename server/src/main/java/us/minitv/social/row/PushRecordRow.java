package us.minitv.social.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table push_records. Generated from the Python models. */
@Data
public class PushRecordRow {
    private Long id;
    private Long dramaId;
    private String title;
    private String body;
    private Long deliveredCount;
    private LocalDateTime sentAt;
}
