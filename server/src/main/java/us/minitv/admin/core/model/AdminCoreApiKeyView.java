package us.minitv.admin.core.model;

import lombok.Data;

import java.time.LocalDateTime;

/** admin_api_keys joined with the bound admin_users row (list serialisation). */
@Data
public class AdminCoreApiKeyView {
    private Long id;
    private Long adminUserId;
    private String name;
    private String keyPrefix;
    private String scopes;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
    private String revokeReason;
    private String username;
    private String displayName;
}
