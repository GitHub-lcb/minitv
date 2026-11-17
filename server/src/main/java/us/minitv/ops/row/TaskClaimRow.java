package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table task_claims. Generated from the Python models. */
@Data
public class TaskClaimRow {
    private Long id;
    private Long userId;
    private String code;
    private String periodKey;
    private Integer amount;
    private LocalDateTime createdAt;
}
