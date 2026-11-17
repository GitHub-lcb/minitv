package us.minitv.wallet.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table wallet_accounts. Generated from the Python models. */
@Data
public class WalletAccountRow {
    private Long userId;
    private Integer balance;
    private LocalDateTime updatedAt;
}
