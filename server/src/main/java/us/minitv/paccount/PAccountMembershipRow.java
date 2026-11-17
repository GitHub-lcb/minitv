package us.minitv.paccount;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Result of the active subscription + plan lookup behind {@code /v1/me}'s
 * {@code membership} block (billing/service.py active_membership).
 */
@Data
public class PAccountMembershipRow {
    private String planCode;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime passEnd;
    private LocalDateTime canceledAt;
}
