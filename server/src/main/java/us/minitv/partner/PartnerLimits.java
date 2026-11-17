package us.minitv.partner;

import java.util.List;
import java.util.Map;

/** Business constants copied from {@code partner_router}, {@code upload_service} and {@code flow_router}. */
public final class PartnerLimits {

    /** {@code MIN_FREE_EPISODES} / {@code DEFAULT_FREE_EPISODES}. */
    public static final int MIN_FREE_EPISODES = 5;
    public static final int DEFAULT_FREE_EPISODES = 5;
    /** {@code MAX_EPISODES}. */
    public static final int MAX_EPISODES = 300;

    /** {@code upload_service.MAX_BYTES} = 200 MB. */
    public static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;
    /** {@code upload_service.ALLOWED_MIME}. */
    public static final List<String> ALLOWED_MIME = List.of("video/mp4", "video/quicktime");

    /** {@code flow_router.COVER_MAX_BYTES} = 5 MB. */
    public static final int COVER_MAX_BYTES = 5 * 1024 * 1024;
    /** {@code flow_router.COVER_EXTS}. */
    public static final List<String> COVER_EXTS = List.of("jpg", "png", "webp");
    /** {@code flow_router.COVER_MIME}. */
    public static final Map<String, String> COVER_MIME = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private PartnerLimits() {
    }
}
