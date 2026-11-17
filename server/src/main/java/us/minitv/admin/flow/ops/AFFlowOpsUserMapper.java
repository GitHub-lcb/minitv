package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.account.row.UserRow;
import us.minitv.wallet.row.CoinLedgerRow;
import us.minitv.wallet.row.WalletAccountRow;

import java.util.List;

/** End users, their wallet and coin ledger. */
@Mapper
public interface AFFlowOpsUserMapper {

    @Select("SELECT * FROM users WHERE id = #{id}")
    UserRow findUser(@Param("id") long id);

    @Select("SELECT id FROM users WHERE email = #{email} AND id <> #{id} LIMIT 1")
    Long findOtherUserIdByEmail(@Param("email") String email, @Param("id") long id);

    @Update("UPDATE users SET display_name = #{displayName}, email = #{email,jdbcType=VARCHAR}, gender = #{gender}, "
            + "avatar_url = #{avatarUrl}, bio = #{bio}, region = #{region}, locale = #{locale}, "
            + "updated_at = NOW() WHERE id = #{id}")
    int updateUser(UserRow row);

    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId}")
    WalletAccountRow findWallet(@Param("userId") long userId);

    @Select("SELECT * FROM wallet_accounts WHERE user_id = #{userId} FOR UPDATE")
    WalletAccountRow lockWallet(@Param("userId") long userId);

    @Insert("INSERT INTO wallet_accounts (user_id, balance) VALUES (#{userId}, #{balance})")
    int insertWallet(@Param("userId") long userId, @Param("balance") int balance);

    @Update("UPDATE wallet_accounts SET balance = #{balance}, updated_at = NOW() WHERE user_id = #{userId}")
    int updateWalletBalance(@Param("userId") long userId, @Param("balance") int balance);

    @Insert("INSERT INTO coin_ledger (user_id, amount, balance_after, biz_type, biz_key, note) "
            + "VALUES (#{userId}, #{amount}, #{balanceAfter}, #{bizType}, #{bizKey}, #{note})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCoinLedger(CoinLedgerRow row);

    /** listUsers() lives in afflowops_users.xml because the `q` filter is dynamic. */
    List<UserRow> searchUsers(@Param("needle") String needle, @Param("like") String like);
}
