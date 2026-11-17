package us.minitv.psocial.dto;

import lombok.Data;

/** A {@code GROUP BY target_id ... COUNT(*)} row (reaction like counts). */
@Data
public class PSocialCountRow {
    private Long targetId;
    private Long total;
}
