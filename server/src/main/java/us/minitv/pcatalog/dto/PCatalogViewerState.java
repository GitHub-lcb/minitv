package us.minitv.pcatalog.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** (member, unlocked episode ids, episode_id -> resume seconds) for one viewer. */
@Data
public class PCatalogViewerState {
    private boolean member;
    private Set<Long> unlocked = new HashSet<>();
    private Map<Long, Integer> progress = new HashMap<>();
}
