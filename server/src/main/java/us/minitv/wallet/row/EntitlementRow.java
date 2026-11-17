package us.minitv.wallet.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table entitlements. Generated from the Python models. */
@Data
public class EntitlementRow {
    private Long id;
    private Long userId;
    private Long episodeId;
    private String entitlementType;
    private LocalDateTime createdAt;
}
