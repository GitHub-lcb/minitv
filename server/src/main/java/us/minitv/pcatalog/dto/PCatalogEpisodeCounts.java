package us.minitv.pcatalog.dto;

import lombok.Data;

/** Episode counts for one drama used by the For-You card ("共 N 集"). */
@Data
public class PCatalogEpisodeCounts {
    private Long dramaId;
    /** Episodes in any status. */
    private Long total;
    /** Episodes with status = published. */
    private Long published;
}
