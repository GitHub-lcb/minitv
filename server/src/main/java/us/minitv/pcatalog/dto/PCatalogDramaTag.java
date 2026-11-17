package us.minitv.pcatalog.dto;

import lombok.Data;

/** Flattened drama_tags JOIN tags row: enabled tags per drama, tag-id order. */
@Data
public class PCatalogDramaTag {
    private Long dramaId;
    private Long tagId;
    private String nameEn;
    private String nameZh;
    private String category;
    private String status;
}
