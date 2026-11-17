package us.minitv.paccount;

import jakarta.validation.constraints.NotNull;

/**
 * POST /v1/auth/email/code body (public_flow.py CodeIn). Pydantic's default
 * {@code purpose="login"} is re-applied in the compact constructor.
 */
public record PAccountCodeIn(@NotNull String email, String purpose) {

    public PAccountCodeIn {
        if (purpose == null) {
            purpose = "login";
        }
    }
}
