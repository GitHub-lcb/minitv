package us.minitv.pcatalog.service;

import org.springframework.stereotype.Service;
import us.minitv.content.row.TagRow;
import us.minitv.pcatalog.dto.PCatalogCandidate;
import us.minitv.pcatalog.dto.PCatalogFeatureStore;
import us.minitv.pcatalog.support.PCatalogBlake2b;
import us.minitv.pcatalog.support.PCatalogFeedConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Weighted scoring and diversity re-ranking - a 1:1 port of
 * {@code backend/app/modules/recommend/ranking.py}.
 */
@Service
public class PCatalogRanking {

    private final PCatalogSignals signals;

    public PCatalogRanking(PCatalogSignals signals) {
        this.signals = signals;
    }

    /** Full scoring pass: features -> weighted score -> diversity re-rank. */
    public List<PCatalogCandidate> rank(PCatalogFeatureStore store, List<PCatalogCandidate> candidates,
                                        String seed) {
        scoreCandidates(store, candidates, seed);
        return diversify(store, candidates);
    }

    /** Fill {@code candidate.features} and {@code candidate.score} in place. */
    public void scoreCandidates(PCatalogFeatureStore store, List<PCatalogCandidate> candidates, String seed) {
        Map<String, Double> weights = PCatalogFeedConfig.qualityWeights();
        for (PCatalogCandidate candidate : candidates) {
            Map<String, Double> features = new java.util.LinkedHashMap<>();
            double quality = 0.0;
            for (Map.Entry<String, BiFunction<PCatalogFeatureStore, PCatalogCandidate, Double>> entry
                    : signals.registry().entrySet()) {
                double value = clamp(entry.getValue().apply(store, candidate));
                features.put(entry.getKey(), value);
                quality += weights.getOrDefault(entry.getKey(), 0.0) * value;
            }
            candidate.setFeatures(features);
            candidate.setScore(quality + adjustments(store, candidate) + noise(seed, candidate));
        }
    }

    /**
     * Greedy re-ordering: no long run of one main category. Walks the
     * score-sorted list and takes the best item that does not exceed the
     * per-window quota for its main tag, deferring the rest.
     */
    public List<PCatalogCandidate> diversify(PCatalogFeatureStore store, List<PCatalogCandidate> candidates) {
        List<PCatalogCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(PCatalogCandidate::getScore).reversed());
        List<PCatalogCandidate> pending = new ArrayList<>(ordered);
        List<PCatalogCandidate> result = new ArrayList<>();

        while (!pending.isEmpty()) {
            boolean placed = false;
            for (int index = 0; index < pending.size(); index++) {
                PCatalogCandidate candidate = pending.get(index);
                TagRow tag = signals.mainTag(store, candidate);
                if (tag != null) {
                    int sameInWindow = 0;
                    int from = Math.max(0, result.size() - (PCatalogFeedConfig.DIVERSITY_WINDOW - 1));
                    for (int position = from; position < result.size(); position++) {
                        if (sameTag(tag, signals.mainTag(store, result.get(position)))) {
                            sameInWindow++;
                        }
                    }
                    if (sameInWindow >= PCatalogFeedConfig.MAX_SAME_MAIN_TAG) {
                        continue;
                    }
                }
                result.add(candidate);
                pending.remove(index);
                placed = true;
                break;
            }
            if (!placed) {
                // Nothing fits the quota; keep the remaining score order as-is.
                result.addAll(pending);
                break;
            }
        }
        return result;
    }

    private static boolean sameTag(TagRow left, TagRow right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getId().equals(right.getId());
    }

    /** Additive nudges that are not "quality" and do not belong in the weights. */
    private double adjustments(PCatalogFeatureStore store, PCatalogCandidate candidate) {
        double value = 0.0;
        if (candidate.isResumable()) {
            value += PCatalogFeedConfig.param("RESUME_BOOST", PCatalogFeedConfig.RESUME_BOOST);
        }
        int impressions = store.getImpressions().getOrDefault(candidate.getDrama().getId(), 0);
        if (impressions > 0) {
            value -= PCatalogFeedConfig.param("FATIGUE_PENALTY", PCatalogFeedConfig.FATIGUE_PENALTY)
                    * Math.log1p(impressions);
        }
        return value;
    }

    /**
     * Deterministic per-(session, drama) jitter: seeded rather than random so the
     * same session replays the same order while the client pages through it.
     */
    private static double noise(String seed, PCatalogCandidate candidate) {
        double unit = PCatalogBlake2b.unit(seed + ":" + candidate.getDrama().getId());
        double amplitude = PCatalogFeedConfig.param("EXPLORE_NOISE", PCatalogFeedConfig.EXPLORE_NOISE);
        return (unit - 0.5) * 2.0 * amplitude;
    }

    private static double clamp(double value) {
        return value < 0.0 ? 0.0 : value > 1.0 ? 1.0 : value;
    }
}
