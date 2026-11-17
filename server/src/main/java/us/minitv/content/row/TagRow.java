package us.minitv.content.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table tags. Generated from the Python models. */
@Data
public class TagRow {
    private Long id;
    private String nameEn;
    private String nameZh;
    private String category;
    private String status;
    private LocalDateTime createdAt;
}
