package us.minitv.paccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /v1/auth/google body (GoogleIn). */
public record PAccountGoogleIn(@JsonProperty("id_token") @NotNull @Size(min = 1) String idToken) {
}
