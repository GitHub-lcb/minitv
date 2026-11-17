package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table admin_api_keys. Generated from the Python models. */
@Data
public class AdminApiKeyRow {
    private Long id;
    private Long adminUserId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private String scopes;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
    private Long revokedBy;
    private String revokeReason;
}
