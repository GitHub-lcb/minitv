package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table drama_home_groups. Generated from the Python models. */
@Data
public class DramaHomeGroupRow {
    private Long groupId;
    private Long dramaId;
    private Integer positionInGroup;
    private LocalDateTime createdAt;
}
