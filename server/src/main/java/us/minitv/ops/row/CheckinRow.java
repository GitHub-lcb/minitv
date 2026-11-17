package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table checkins. Generated from the Python models. */
@Data
public class CheckinRow {
    private Long id;
    private Long userId;
    private LocalDate day;
    private Integer streak;
    private Integer amount;
    private LocalDateTime createdAt;
}
