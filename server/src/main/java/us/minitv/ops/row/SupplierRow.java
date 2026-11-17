package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table suppliers. Generated from the Python models. */
@Data
public class SupplierRow {
    private Long id;
    private String name;
    private String contactName;
    private String contactEmail;
    private String status;
    private Integer revenueShareBps;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
