package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table reward_configs. Generated from the Python models. */
@Data
public class RewardConfigRow {
    private Long id;
    private String code;
    private String amounts;
    private LocalDate effectiveOn;
    private LocalDateTime createdAt;
}
