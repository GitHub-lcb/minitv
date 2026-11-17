package us.minitv.pcatalog.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** One drama reaction joined to its drama's tags (tag-affinity input). */
@Data
public class PCatalogAffinityReaction {
    private Long tagId;
    private LocalDateTime createdAt;
}
