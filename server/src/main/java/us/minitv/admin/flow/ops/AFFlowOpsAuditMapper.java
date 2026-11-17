package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.common.row.AuditLogRow;

import java.util.List;

/** audit_logs writes/reads used by the admin flow/ops port. */
@Mapper
public interface AFFlowOpsAuditMapper {

    @Insert("INSERT INTO audit_logs (actor_type, actor_id, action, target_type, target_id, payload) "
            + "VALUES (#{actorType}, #{actorId}, #{action}, #{targetType}, #{targetId}, #{payload})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLogRow row);

    @Select("SELECT * FROM audit_logs WHERE action = 'reward_config_update' ORDER BY id DESC LIMIT #{limit}")
    List<AuditLogRow> recentRewardAudits(@Param("limit") int limit);

    @Select("SELECT * FROM audit_logs WHERE target_type = 'user' AND target_id = #{userId} "
            + "AND action IN ('membership_grant', 'membership_revoke') ORDER BY id DESC LIMIT 1")
    AuditLogRow findLastMembershipAudit(@Param("userId") long userId);
}
