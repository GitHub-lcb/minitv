package us.minitv.paccount;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.wallet.row.CoinLedgerRow;

import java.util.List;

/** coin_ledger access: one row per coin change, globally unique biz_key. */
@Mapper
public interface PAccountLedgerMapper {

    @Insert("INSERT INTO coin_ledger (user_id, amount, balance_after, biz_type, biz_key, episode_id, note) "
            + "VALUES (#{userId}, #{amount}, #{balanceAfter}, #{bizType}, #{bizKey}, NULL, '')")
    int insert(@Param("userId") long userId,
               @Param("amount") int amount,
               @Param("balanceAfter") int balanceAfter,
               @Param("bizType") String bizType,
               @Param("bizKey") String bizKey);

    @Select("SELECT * FROM coin_ledger WHERE user_id = #{userId} ORDER BY id DESC")
    List<CoinLedgerRow> listByUser(@Param("userId") long userId);
}
