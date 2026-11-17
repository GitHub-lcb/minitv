package us.minitv.paccount;

import jakarta.validation.constraints.NotNull;

/** POST /v1/auth/email/register body (RegisterIn); password is rejected by the controller. */
public record PAccountRegisterIn(@NotNull String email, @NotNull String code, String password) {
}
