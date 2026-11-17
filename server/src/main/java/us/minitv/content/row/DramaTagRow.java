package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table drama_tags. Generated from the Python models. */
@Data
public class DramaTagRow {
    private Long dramaId;
    private Long tagId;
    private LocalDateTime createdAt;
}
