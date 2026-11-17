package us.minitv.billing.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table orders. Generated from the Python models. */
@Data
public class OrderRow {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long planVersionId;
    private Integer amountCents;
    private String currency;
    private String billingMode;
    private String status;
    private String externalTxnId;
    private LocalDateTime paidAt;
    private Long sourceDramaId;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
