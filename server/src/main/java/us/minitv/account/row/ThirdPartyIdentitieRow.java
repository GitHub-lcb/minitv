package us.minitv.account.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table third_party_identities. Generated from the Python models. */
@Data
public class ThirdPartyIdentitieRow {
    private Long id;
    private Long userId;
    private String provider;
    private String providerUserId;
    private LocalDateTime createdAt;
}
