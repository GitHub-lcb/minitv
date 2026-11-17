package us.minitv.admin.core.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** supplier_settlements joined with the supplier name (list serialisation). */
@Data
public class AdminCoreSettlementView {
    private Long id;
    private Long supplierId;
    private String supplierName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String currency;
    private Long grossCents;
    private Long revenueCents;
    private Integer effectiveShareBps;
    private Integer orderCount;
    private String status;
    private String note;
    private LocalDateTime generatedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime paidAt;
}
