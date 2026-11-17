package us.minitv.paccount;

import jakarta.validation.constraints.NotNull;

/** POST /v1/auth/email/authenticate and /v1/auth/email/login body (LoginIn). */
public record PAccountLoginIn(@NotNull String email, @NotNull String code) {
}
