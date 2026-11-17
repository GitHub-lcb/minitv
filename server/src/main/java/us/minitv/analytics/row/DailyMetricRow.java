package us.minitv.analytics.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table daily_metrics. Generated from the Python models. */
@Data
public class DailyMetricRow {
    private Long id;
    private LocalDate businessDay;
    private String timezone;
    private Integer activeUsers;
    private Integer newUsers;
    private Integer plays;
    private Integer completes;
    private Integer paidOrders;
    private Integer revenueCents;
    private Integer coinSpend;
    private Integer unlockCount;
    private Integer completionRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
