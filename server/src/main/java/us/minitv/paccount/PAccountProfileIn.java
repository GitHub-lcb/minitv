package us.minitv.paccount;

import com.fasterxml.jackson.annotation.JsonProperty;

/** PATCH /v1/me body (public_flow.py ProfileIn); every field is optional. */
public record PAccountProfileIn(
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar_url") String avatarUrl,
        String gender,
        String bio,
        String birthday,
        String region,
        String locale) {
}
