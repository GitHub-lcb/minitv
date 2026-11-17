package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table ad_claims. Generated from the Python models. */
@Data
public class AdClaimRow {
    private Long id;
    private Long userId;
    private LocalDate day;
    private Integer amount;
    private LocalDateTime createdAt;
}
