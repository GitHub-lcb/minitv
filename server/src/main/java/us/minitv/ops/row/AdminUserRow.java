package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table admin_users. Generated from the Python models. */
@Data
public class AdminUserRow {
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private Long supplierId;
    private String disableReason;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
