package us.minitv.psocial.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/** Body of POST /v1/reactions. Python {@code ReactionIn}. */
public record PSocialReactionIn(
        @JsonProperty("target_type") @NotNull String targetType,
        @JsonProperty("target_id") @NotNull Long targetId,
        @JsonProperty("kind") @NotNull String kind,
        @JsonProperty("remove") Boolean remove) {

    public boolean removeFlag() {
        return Boolean.TRUE.equals(remove);
    }
}
