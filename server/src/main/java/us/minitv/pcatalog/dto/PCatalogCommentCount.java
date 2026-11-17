package us.minitv.pcatalog.dto;

import lombok.Data;

/** Comment count for one episode. */
@Data
public class PCatalogCommentCount {
    private Long episodeId;
    private Long total;
}
