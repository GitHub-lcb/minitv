package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table api_key_events. Generated from the Python models. */
@Data
public class ApiKeyEventRow {
    private Long id;
    private Long keyId;
    private Long staffId;
    private String method;
    private String path;
    private Integer statusCode;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
