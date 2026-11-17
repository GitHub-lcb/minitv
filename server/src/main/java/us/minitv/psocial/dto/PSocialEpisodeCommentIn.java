package us.minitv.psocial.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /v1/episodes/{{episode_id}}/comments.
 *
 * <p>Python's {@code EpisodeCommentIn} is {@code Field(min_length=1, max_length=500)};
 * whitespace-only input passes length validation and is rejected later with
 * the explicit {@code "comment is empty"} 422.</p>
 */
public record PSocialEpisodeCommentIn(
        @JsonProperty("body") @NotNull @Size(min = 1, max = 500) String body) {
}
