package us.minitv.pcatalog.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tunable knobs for the For-You feed, ported 1:1 from
 * {@code backend/app/modules/recommend/config.py}.
 *
 * <p>Environment overrides are read at call time (not class-init time) so a
 * restart-free reload picks them up exactly like the Python module did.
 */
public final class PCatalogFeedConfig {

    /** What a signal returns when it has no evidence at all. */
    public static final double NEUTRAL = 0.5;

    // ---- paging ----
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 20;
    public static final int PRELOAD_REMAINING = 2;

    // ---- scoring: quality signals ----
    public static final Map<String, Double> QUALITY_WEIGHTS = qualityWeightsLiteral();

    // ---- scoring: additive adjustments ----
    public static final double RESUME_BOOST = 0.35;
    public static final double FATIGUE_PENALTY = 0.09;
    public static final double EXPLORE_NOISE = 0.10;
    public static final int EXPLORE_QUOTA = 1;

    // ---- signal parameters ----
    public static final int COMPLETION_WINDOW_DAYS = 30;
    public static final double COMPLETION_PRIOR_STRENGTH = 20.0;
    public static final double DEFAULT_GLOBAL_COMPLETION = 0.35;
    public static final int HOTNESS_WINDOW_DAYS = 7;
    public static final double FRESHNESS_TAU_DAYS = 21.0;
    public static final int AFFINITY_WINDOW_DAYS = 45;
    public static final double AFFINITY_DECAY_DAYS = 14.0;

    // ---- recall / session ----
    public static final int RECALL_POOL_MULTIPLIER = 6;
    public static final int MIN_POOL_SIZE = 12;
    public static final int SOURCE_QUOTA = 3;
    public static final int RESUME_QUOTA = 2;
    public static final long IMPRESSION_TTL_SECONDS = 7L * 24 * 3600;
    public static final long PLAN_TTL_SECONDS = 7L * 24 * 3600;

    // ---- diversity ----
    public static final int DIVERSITY_WINDOW = 5;
    public static final int MAX_SAME_MAIN_TAG = 2;

    // ---- resume ----
    public static final double RESUME_MAX_RATIO = 0.95;

    private PCatalogFeedConfig() {
    }

    private static Map<String, Double> qualityWeightsLiteral() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("completion", 0.30);
        weights.put("affinity", 0.26);
        weights.put("hotness", 0.18);
        weights.put("freshness", 0.14);
        weights.put("depth", 0.12);
        return java.util.Collections.unmodifiableMap(weights);
    }

    /** Weights for the quality signals, re-normalised to sum to 1. */
    public static Map<String, Double> qualityWeights() {
        Map<String, Double> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : QUALITY_WEIGHTS.entrySet()) {
            raw.put(entry.getKey(), envFloat("FEED_W_" + entry.getKey().toUpperCase(java.util.Locale.ROOT), entry.getValue()));
        }
        double total = 0.0;
        for (double value : raw.values()) {
            total += Math.max(0.0, value);
        }
        if (total <= 0.0) {
            return QUALITY_WEIGHTS;
        }
        Map<String, Double> normalised = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            normalised.put(entry.getKey(), Math.max(0.0, entry.getValue()) / total);
        }
        return normalised;
    }

    /** Read a tunable parameter, allowing a {@code FEED_<NAME>} env override. */
    public static double param(String name, double fallback) {
        return envFloat("FEED_" + name, fallback);
    }

    private static double envFloat(String name, double fallback) {
        String raw = us.minitv.common.Env.get(name);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
