package us.minitv.pcatalog.dto;

import lombok.Data;
import us.minitv.content.row.TagRow;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the feed signals need, pre-aggregated (FeatureStore in Python). */
@Data
public class PCatalogFeatureStore {
    private LocalDateTime now;
    private boolean member;

    // per-drama aggregates (keyed by drama id)
    private Map<Long, Long> plays = new HashMap<>();     // starts, 30d
    private Map<Long, Long> completes = new HashMap<>(); // completes, 30d
    private Map<Long, Long> hot = new HashMap<>();       // starts, 7d
    private Map<Long, Long> published = new HashMap<>(); // published episodes
    private Map<Long, Long> total = new HashMap<>();     // episodes in any status
    private Map<Long, List<TagRow>> tags = new LinkedHashMap<>();

    // normalisation helpers computed from the candidate pool
    private double globalCompletion = 0.35;
    private long maxHot = 1L;
    private double maxTagScore = 1.0;

    // per-viewer state
    private Map<Long, Double> userTagScores = new HashMap<>();
    private Map<Long, Integer> impressions = new HashMap<>();
    private Map<Long, Integer> progress = new HashMap<>(); // episode_id -> seconds
    private Set<Long> completedEpisodes = new HashSet<>();
    private Set<Long> unlockedEpisodes = new HashSet<>();
}
