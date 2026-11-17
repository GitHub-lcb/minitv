package us.minitv.pcatalog.dto;

import lombok.Data;

/** Like total for one drama. */
@Data
public class PCatalogReactionTotal {
    private Long targetId;
    private Long total;
}
