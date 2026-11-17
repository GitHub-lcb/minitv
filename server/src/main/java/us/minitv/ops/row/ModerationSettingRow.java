package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table moderation_settings. Generated from the Python models. */
@Data
public class ModerationSettingRow {
    private Integer id;
    private Boolean enabled;
    private String baseUrl;
    private String model;
    private String categories;
    private String systemPrompt;
    private Double approveThreshold;
    private Double blockThreshold;
    private String fallbackAction;
    private Integer timeoutSeconds;
    private String apiKeySecret;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
