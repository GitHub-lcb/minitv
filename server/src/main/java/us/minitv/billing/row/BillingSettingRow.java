package us.minitv.billing.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table billing_settings. Generated from the Python models. */
@Data
public class BillingSettingRow {
    private Integer id;
    private String billingMode;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
