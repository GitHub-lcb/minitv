package us.minitv.paccount;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** subscriptions + plans join used by GET /v1/me's membership block. */
@Mapper
public interface PAccountMembershipMapper {

    @Select("SELECT s.current_period_end, s.pass_end, s.canceled_at, p.code AS plan_code "
            + "FROM subscriptions s JOIN plans p ON p.id = s.plan_id "
            + "WHERE s.user_id = #{userId} AND s.status = 'active' LIMIT 1")
    PAccountMembershipRow findActiveMembership(@Param("userId") long userId);
}
