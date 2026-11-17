package us.minitv.account.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table verification_codes. Generated from the Python models. */
@Data
public class VerificationCodeRow {
    private Long id;
    private String email;
    private String purpose;
    private String codeHash;
    private Integer attempts;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
