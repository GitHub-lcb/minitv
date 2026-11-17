package us.minitv.common.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table audit_logs. Generated from the Python models. */
@Data
public class AuditLogRow {
    private Long id;
    private String actorType;
    private Long actorId;
    private String action;
    private String targetType;
    private Long targetId;
    private String payload;
    private LocalDateTime createdAt;
}
