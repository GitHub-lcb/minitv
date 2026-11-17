package us.minitv.pcatalog.dto;

import lombok.Data;
import us.minitv.content.row.EpisodeRow;

/** Result of choosing the episode a feed slot opens on. */
@Data
public class PCatalogEntryPick {
    private EpisodeRow episode;
    private int resumeSeconds;
    private boolean resumable;
}
