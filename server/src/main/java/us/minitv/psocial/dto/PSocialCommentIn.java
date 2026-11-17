package us.minitv.psocial.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of the legacy POST /v1/comments alias.
 *
 * <p>Python {@code CommentIn}: drama_id required, episode_id optional,
 * body {@code min_length=1, max_length=1000}. The handler then re-validates the
 * text as an {@code EpisodeCommentIn} (max 500).</p>
 */
public record PSocialCommentIn(
        @JsonProperty("drama_id") @NotNull Long dramaId,
        @JsonProperty("episode_id") Long episodeId,
        @JsonProperty("body") @NotNull @Size(min = 1, max = 1000) String body) {
}
