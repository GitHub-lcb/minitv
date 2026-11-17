package us.minitv.admin.flow.content;

import java.util.Map;
import java.util.Set;

/**
 * Status machines, mirrored 1:1 from
 * {@code backend/app/modules/content/service.py}.
 *
 * <p>The same matrices are enforced by MySQL triggers (V3 migration), so an
 * illegal transition must be rejected before it reaches the database with the
 * exact Python message: {@code illegal episode transition: from -> to}.</p>
 */
final class AFContentTransitions {

    static final Set<String> DRAMA_STATUSES =
            Set.of("draft", "in_review", "published", "unpublished");

    static final Set<String> EPISODE_STATUSES = Set.of(
            "not_uploaded", "transcoding", "transcode_failed", "in_review", "published", "unpublished");

    static final Map<String, Set<String>> DRAMA_TRANSITIONS = Map.of(
            "draft", Set.of("in_review"),
            "in_review", Set.of("draft", "published"),
            "published", Set.of("unpublished"),
            "unpublished", Set.of("in_review", "published"));

    static final Map<String, Set<String>> EPISODE_TRANSITIONS = Map.of(
            "not_uploaded", Set.of("transcoding"),
            "transcoding", Set.of("in_review", "transcode_failed"),
            "transcode_failed", Set.of("transcoding"),
            "in_review", Set.of("published", "not_uploaded"),
            "published", Set.of("unpublished", "not_uploaded"),
            "unpublished", Set.of("published", "not_uploaded"));

    private AFContentTransitions() {
    }

    static void requireDramaTransition(String from, String to) {
        if (to == null || !DRAMA_STATUSES.contains(to)
                || !DRAMA_TRANSITIONS.getOrDefault(String.valueOf(from), Set.of()).contains(to)) {
            throw new IllegalTransition("drama", from, to);
        }
    }

    static void requireEpisodeTransition(String from, String to) {
        if (to == null || !EPISODE_STATUSES.contains(to)
                || !EPISODE_TRANSITIONS.getOrDefault(String.valueOf(from), Set.of()).contains(to)) {
            throw new IllegalTransition("episode", from, to);
        }
    }

    /** Python's {@code InvalidTransition}; message is used verbatim as 409 detail. */
    static final class IllegalTransition extends RuntimeException {

        private final String entity;
        private final String fromStatus;
        private final String toStatus;

        IllegalTransition(String entity, String fromStatus, String toStatus) {
            super("illegal " + entity + " transition: " + fromStatus + " -> " + toStatus);
            this.entity = entity;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
        }

        String entity() {
            return entity;
        }

        String fromStatus() {
            return fromStatus;
        }

        String toStatus() {
            return toStatus;
        }
    }
}
