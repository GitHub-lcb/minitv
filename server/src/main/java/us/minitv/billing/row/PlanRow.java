package us.minitv.billing.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table plans. Generated from the Python models. */
@Data
public class PlanRow {
    private Long id;
    private String code;
    private String nameEn;
    private String nameZh;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
