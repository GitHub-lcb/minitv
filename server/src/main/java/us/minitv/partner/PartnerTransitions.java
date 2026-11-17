package us.minitv.partner;

import java.util.Map;
import java.util.Set;

/**
 * Episode status machine, copied 1:1 from {@code app.modules.content.service}.
 *
 * <p>The database also enforces these moves with a trigger (V3 migration); the
 * application-level check exists so an illegal move produces the same 409 as
 * the Python service instead of a SQL error.
 */
public final class PartnerTransitions {

    private static final Map<String, Set<String>> EPISODE = Map.of(
            "not_uploaded", Set.of("transcoding"),
            "transcoding", Set.of("in_review", "transcode_failed"),
            "transcode_failed", Set.of("transcoding"),
            "in_review", Set.of("published", "not_uploaded"),
            "published", Set.of("unpublished", "not_uploaded"),
            "unpublished", Set.of("published", "not_uploaded"));

    private PartnerTransitions() {
    }

    public static void requireEpisode(String fromStatus, String toStatus) {
        Set<String> allowed = EPISODE.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw new PartnerInvalidTransition("episode", fromStatus, toStatus);
        }
    }
}
