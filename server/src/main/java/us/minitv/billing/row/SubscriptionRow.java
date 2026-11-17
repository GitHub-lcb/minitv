package us.minitv.billing.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table subscriptions. Generated from the Python models. */
@Data
public class SubscriptionRow {
    private Long id;
    private Long userId;
    private Long planId;
    private Long planVersionId;
    private String status;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime canceledAt;
    private String stripeCustomerId;
    private String stripeSubscriptionId;
    private String stripePriceId;
    private String billingMode;
    private LocalDateTime passEnd;
    private Long sourceDramaId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
