package us.minitv.paccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/auth/facebook body (FacebookIn): either a Limited Login id_token or
 * an SDK access token, never both required. Pydantic's Field(min_length=1)
 * becomes {@code @Size(min = 1)}; both-absent is rejected by the controller.
 */
public record PAccountFacebookIn(
        @JsonProperty("access_token") @Size(min = 1) String accessToken,
        @JsonProperty("id_token") @Size(min = 1) String idToken) {
}
