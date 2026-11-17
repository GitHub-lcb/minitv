package us.minitv.paccount;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.ops.row.AdClaimRow;
import us.minitv.ops.row.CheckinRow;
import us.minitv.ops.row.RewardConfigRow;
import us.minitv.ops.row.TaskClaimRow;

import java.time.LocalDate;

/**
 * reward_configs / checkins / ad_claims / task_claims access for the public
 * reward flow. amount(s) are stored as JSON and read as raw strings.
 */
@Mapper
public interface PAccountRewardMapper {

    /** Newest config row effective on or before {@code day}; null means defaults apply. */
    @Select("SELECT * FROM reward_configs WHERE code = #{code} AND effective_on <= #{day} "
            + "ORDER BY effective_on DESC LIMIT 1")
    RewardConfigRow findActiveConfig(@Param("code") String code, @Param("day") LocalDate day);

    @Select("SELECT * FROM checkins WHERE user_id = #{userId} AND day = #{day}")
    CheckinRow findCheckin(@Param("userId") long userId, @Param("day") LocalDate day);

    @Insert("INSERT INTO checkins (user_id, day, streak, amount) "
            + "VALUES (#{userId}, #{day}, #{streak}, #{amount})")
    int insertCheckin(@Param("userId") long userId,
                      @Param("day") LocalDate day,
                      @Param("streak") int streak,
                      @Param("amount") int amount);

    @Select("SELECT * FROM ad_claims WHERE user_id = #{userId} AND day = #{day}")
    AdClaimRow findAdClaim(@Param("userId") long userId, @Param("day") LocalDate day);

    @Insert("INSERT INTO ad_claims (user_id, day, amount) VALUES (#{userId}, #{day}, #{amount})")
    int insertAdClaim(@Param("userId") long userId,
                      @Param("day") LocalDate day,
                      @Param("amount") int amount);

    @Select("SELECT * FROM task_claims WHERE user_id = #{userId} AND code = #{code} "
            + "AND period_key = #{periodKey}")
    TaskClaimRow findTaskClaim(@Param("userId") long userId,
                               @Param("code") String code,
                               @Param("periodKey") String periodKey);

    @Insert("INSERT INTO task_claims (user_id, code, period_key, amount) "
            + "VALUES (#{userId}, #{code}, #{periodKey}, #{amount})")
    int insertTaskClaim(@Param("userId") long userId,
                        @Param("code") String code,
                        @Param("periodKey") String periodKey,
                        @Param("amount") int amount);
}
