package us.minitv.pcatalog.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** One playback event joined to its drama's tags (tag-affinity input). */
@Data
public class PCatalogAffinityEvent {
    private Long tagId;
    private String kind;
    private LocalDateTime createdAt;
}
