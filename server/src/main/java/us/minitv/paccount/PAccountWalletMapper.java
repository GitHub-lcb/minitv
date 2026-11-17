package us.minitv.paccount;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.wallet.row.WalletAccountRow;

/** wallet_accounts access: balance is a materialised aggregate over coin_ledger. */
@Mapper
public interface PAccountWalletMapper {

    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId}")
    WalletAccountRow findById(@Param("userId") long userId);

    /** Python's {@code session.get(WalletAccount, user_id, with_for_update=True)}. */
    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId} FOR UPDATE")
    WalletAccountRow findForUpdate(@Param("userId") long userId);

    @Insert("INSERT INTO wallet_accounts (user_id, balance) VALUES (#{userId}, #{balance})")
    int insert(@Param("userId") long userId, @Param("balance") int balance);

    @Update("UPDATE wallet_accounts SET balance = #{balance}, updated_at = now() WHERE user_id = #{userId}")
    int updateBalance(@Param("userId") long userId, @Param("balance") int balance);
}
