package us.minitv.pcatalog.dto;

import lombok.Data;

/** Per-drama playback aggregates over the feed windows. */
@Data
public class PCatalogDramaAggregate {
    private Long dramaId;
    /** kind = start inside the 30-day completion window. */
    private Long plays;
    /** kind = complete inside the 30-day completion window. */
    private Long completes;
    /** kind = start inside the 7-day hotness window. */
    private Long hot;
}
