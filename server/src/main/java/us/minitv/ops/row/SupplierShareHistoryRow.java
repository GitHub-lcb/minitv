package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table supplier_share_history. Generated from the Python models. */
@Data
public class SupplierShareHistoryRow {
    private Long id;
    private Long supplierId;
    private Integer shareBps;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Long changedBy;
    private String note;
}
