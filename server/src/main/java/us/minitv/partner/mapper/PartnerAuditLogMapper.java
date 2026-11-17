package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import us.minitv.common.row.AuditLogRow;

/** Audit-trail inserts (shared table, owned by nobody; each module writes its own rows). */
@Mapper
public interface PartnerAuditLogMapper {

    int insertAuditLog(AuditLogRow row);
}
