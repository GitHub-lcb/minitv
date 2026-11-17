package us.minitv.ops.row;

import lombok.Data;

import java.time.LocalDate;

/** Row for table banners. Generated from the Python models. */
@Data
public class BannerRow {
    private Long id;
    private String slot;
    private Long dramaId;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private String status;
    private Integer position;
    private String kicker;
    private String headline;
}
