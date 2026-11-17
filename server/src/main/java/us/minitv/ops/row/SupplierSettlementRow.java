package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table supplier_settlements. Generated from the Python models. */
@Data
public class SupplierSettlementRow {
    private Long id;
    private Long supplierId;
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
