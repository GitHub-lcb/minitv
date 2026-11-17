package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table home_groups. Generated from the Python models. */
@Data
public class HomeGroupRow {
    private Long id;
    private String name;
    private Integer position;
    private String status;
    private String style;
    private String badge;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
