package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table media_uploads. Generated from the Python models. */
@Data
public class MediaUploadRow {
    private Long id;
    private Long episodeId;
    private String videoId;
    private String filename;
    private String mime;
    private Long sizeBytes;
    private Boolean vertical;
    private String status;
    private LocalDateTime createdAt;
}
