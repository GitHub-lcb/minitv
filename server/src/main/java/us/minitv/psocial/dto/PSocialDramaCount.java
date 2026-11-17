package us.minitv.psocial.dto;

import lombok.Data;

/** A {@code episodes GROUP BY drama_id} count row ("共 N 集" on cards). */
@Data
public class PSocialDramaCount {
    private Long dramaId;
    private Long total;
}
