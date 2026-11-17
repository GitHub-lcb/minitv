package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.common.row.IdempotencyRecordRow;

/** idempotency_records: one row per (user-scoped) unlock attempt. */
@Mapper
public interface PCommerceIdempotencyMapper {

    @Select("SELECT * FROM idempotency_records WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    IdempotencyRecordRow findByKey(@Param("idempotencyKey") String idempotencyKey);

    @Insert("INSERT INTO idempotency_records (idempotency_key, endpoint, response_payload) "
            + "VALUES (#{idempotencyKey}, #{endpoint}, #{responsePayload})")
    int insert(IdempotencyRecordRow row);
}
