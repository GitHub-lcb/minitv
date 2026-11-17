package us.minitv.wallet.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Row for table coin_ledger. Generated from the Python models. */
@Data
public class CoinLedgerRow {
    private Long id;
    private Long userId;
    private Integer amount;
    private Integer balanceAfter;
    private String bizType;
    private String bizKey;
    private Long episodeId;
    private String note;
    private LocalDateTime createdAt;
}
