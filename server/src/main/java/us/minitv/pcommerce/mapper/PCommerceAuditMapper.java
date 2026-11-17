package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import us.minitv.common.row.AuditLogRow;

/** audit_logs writes owned by this module (bunny callback rejections). */
@Mapper
public interface PCommerceAuditMapper {

    @Insert("INSERT INTO audit_logs (actor_type, actor_id, action, target_type, target_id, payload) "
            + "VALUES (#{actorType}, #{actorId,jdbcType=BIGINT}, #{action}, #{targetType}, "
            + "#{targetId,jdbcType=BIGINT}, #{payload})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLogRow row);
}
