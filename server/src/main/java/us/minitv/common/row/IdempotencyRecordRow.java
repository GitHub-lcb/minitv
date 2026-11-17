package us.minitv.common.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table idempotency_records. Generated from the Python models. */
@Data
public class IdempotencyRecordRow {
    private Long id;
    private String idempotencyKey;
    private String endpoint;
    private String responsePayload;
    private LocalDateTime createdAt;
}
