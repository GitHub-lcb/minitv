package us.minitv.ops.row;

import lombok.Data;


/** Row for table channel_tabs. Generated from the Python models. */
@Data
public class ChannelTabRow {
    private Long id;
    private String code;
    private String titleEn;
    private String titleZh;
    private Integer position;
    private Boolean enabled;
    private Boolean locked;
    private Long groupId;
}
