package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.EntitlementRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.util.List;

/** wallet_accounts / coin_ledger / entitlements. */
@Mapper
public interface PCommerceWalletMapper {

    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId}")
    WalletAccountRow findWallet(@Param("userId") long userId);

    /** session.get(..., with_for_update=True): row lock inside the charge transaction. */
    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId} FOR UPDATE")
    WalletAccountRow lockWallet(@Param("userId") long userId);

    @Update("UPDATE wallet_accounts SET balance = #{balance}, updated_at = now() WHERE user_id = #{userId}")
    int updateBalance(@Param("userId") long userId, @Param("balance") int balance);

    @Select("SELECT balance FROM wallet_accounts WHERE user_id = #{userId}")
    Integer findBalance(@Param("userId") long userId);

    @Insert("INSERT INTO coin_ledger (user_id, amount, balance_after, biz_type, biz_key, episode_id, note) "
            + "VALUES (#{userId}, #{amount}, #{balanceAfter}, #{bizType}, #{bizKey}, "
            + "#{episodeId,jdbcType=BIGINT}, #{note})")
    int insertLedger(CoinLedgerRow row);

    @Select("SELECT * FROM entitlements WHERE user_id = #{userId} AND episode_id = #{episodeId} "
            + "AND entitlement_type = #{entitlementType} LIMIT 1")
    EntitlementRow findEntitlement(@Param("userId") long userId,
                                   @Param("episodeId") long episodeId,
                                   @Param("entitlementType") String entitlementType);

    @Insert("INSERT INTO entitlements (user_id, episode_id, entitlement_type) "
            + "VALUES (#{userId}, #{episodeId}, #{entitlementType})")
    int insertEntitlement(@Param("userId") long userId,
                          @Param("episodeId") long episodeId,
                          @Param("entitlementType") String entitlementType);

    @Select("SELECT episode_id FROM entitlements WHERE user_id = #{userId} "
            + "AND entitlement_type = #{entitlementType}")
    List<Long> entitlementEpisodeIds(@Param("userId") long userId,
                                     @Param("entitlementType") String entitlementType);
}
