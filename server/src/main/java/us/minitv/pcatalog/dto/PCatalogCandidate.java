package us.minitv.pcatalog.dto;

import lombok.Data;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;

import java.util.LinkedHashMap;
import java.util.Map;

/** One feed slot being scored: a drama plus the episode the player opens on. */
@Data
public class PCatalogCandidate {
    private DramaRow drama;
    private EpisodeRow entryEpisode;
    private String source = "";
    private int resumeSeconds;
    private boolean resumable;
    private Map<String, Double> features = new LinkedHashMap<>();
    private double score;
}
