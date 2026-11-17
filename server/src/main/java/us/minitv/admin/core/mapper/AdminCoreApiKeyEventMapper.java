package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.ops.row.ApiKeyEventRow;

import java.util.List;

/** api_key_events reader (the write side lives in security.ApiKeyEventFilter). */
@Mapper
public interface AdminCoreApiKeyEventMapper {

    @Select("SELECT * FROM api_key_events WHERE key_id = #{keyId} ORDER BY id DESC LIMIT #{limit}")
    List<ApiKeyEventRow> listByKey(@Param("keyId") long keyId, @Param("limit") int limit);
}
