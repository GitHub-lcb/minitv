package us.minitv.admin.flow.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request bodies for the admin episode/drama flow endpoints.
 *
 * <p>Pydantic models in {@code backend/app/modules/ops/flow_router.py} accept
 * snake_case field names, so every component carries an explicit
 * {@code @JsonProperty}. Constraints mirror the {@code Field(...)} arguments;
 * FastAPI answers 422 for violations (Spring does the same through
 * {@code GlobalExceptionHandler}).</p>
 */
public final class AFContentAdminDtos {

    private AFContentAdminDtos() {
    }

    /** {@code EpisodeIn}: episode_number >= 1, title default "", max 200. */
    public record EpisodeIn(
            @JsonProperty("episode_number") @NotNull @Min(1) Integer episodeNumber,
            @JsonProperty("title") @Size(max = 200) String title,
            @JsonProperty("video_provider") @Size(max = 16) String videoProvider,
            @JsonProperty("external_video_id") @Size(max = 128) String externalVideoId,
            @JsonProperty("external_url") @Size(max = 1000) String externalUrl,
            @JsonProperty("aspect_ratio") @Size(max = 8) String aspectRatio,
            @JsonProperty("duration_seconds") @Min(0) Integer durationSeconds) {
    }

    /** {@code BulkIn}: 1 <= count <= 200. */
    public record BulkIn(
            @JsonProperty("count") @NotNull @Min(1) @Max(200) Integer count) {
    }

    /** {@code UploadIn}: filename max 255, size >= 0, duration default 0 and >= 0. */
    public record UploadIn(
            @JsonProperty("filename") @NotNull @Size(max = 255) String filename,
            @JsonProperty("mime") @NotNull String mime,
            @JsonProperty("size_bytes") @NotNull @Min(0) Long sizeBytes,
            @JsonProperty("width") @NotNull Integer width,
            @JsonProperty("height") @NotNull Integer height,
            @JsonProperty("duration_seconds") @Min(0) Integer durationSeconds) {
    }

    /** {@code UploadFailedIn} (optional body): both fields default "". */
    public record UploadFailedIn(
            @JsonProperty("video_id") String videoId,
            @JsonProperty("reason") String reason) {
    }

    /** {@code EpisodeUpdateIn}: partial metadata edit, both fields optional. */
    public record EpisodeUpdateIn(
            @JsonProperty("title") @Size(max = 200) String title,
            @JsonProperty("episode_number") @Min(1) @Max(2000) Integer episodeNumber,
            @JsonProperty("video_provider") @Size(max = 16) String videoProvider,
            @JsonProperty("external_video_id") @Size(max = 128) String externalVideoId,
            @JsonProperty("external_url") @Size(max = 1000) String externalUrl,
            @JsonProperty("aspect_ratio") @Size(max = 8) String aspectRatio) {
    }

    /** {@code StatusIn}: the requested drama status. */
    public record StatusIn(
            @JsonProperty("to_status") @NotNull String toStatus) {
    }

    /** {@code EpisodeBulkStatusIn}: 1..200 ids plus publish|unpublish. */
    public record EpisodeBulkStatusIn(
            @JsonProperty("episode_ids") @NotNull @Size(min = 1, max = 200) List<@NotNull Long> episodeIds,
            @JsonProperty("action") @NotNull @Pattern(regexp = "^(publish|unpublish)$") String action) {
    }

    /** {@code ScheduleIn.at}: ISO-8601 datetime string (parsed in the service). */
    public record ScheduleIn(
            @JsonProperty("at") @NotNull String at) {
    }

    /** {@code DeleteIn}: confirm defaults to false. */
    public record DeleteIn(
            @JsonProperty("confirm") boolean confirm) {
    }
}
