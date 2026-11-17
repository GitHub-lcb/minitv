package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table cdn_purges. Generated from the Python models. */
@Data
public class CdnPurgeRow {
    private Long id;
    private Long episodeId;
    private Long dramaId;
    private String reason;
    private LocalDateTime createdAt;
}
