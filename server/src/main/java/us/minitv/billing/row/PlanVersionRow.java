package us.minitv.billing.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table plan_versions. Generated from the Python models. */
@Data
public class PlanVersionRow {
    private Long id;
    private Long planId;
    private Integer version;
    private Integer priceCents;
    private String currency;
    private String benefits;
    private String note;
    private String stripePriceId;
    private String stripeOneTimePriceId;
    private LocalDateTime createdAt;
}
