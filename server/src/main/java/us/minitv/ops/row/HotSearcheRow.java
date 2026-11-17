package us.minitv.ops.row;

import lombok.Data;


/** Row for table hot_searches. Generated from the Python models. */
@Data
public class HotSearcheRow {
    private Long id;
    private String term;
    private Integer position;
    private Boolean enabled;
}
