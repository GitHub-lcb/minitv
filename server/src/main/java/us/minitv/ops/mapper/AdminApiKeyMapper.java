package us.minitv.ops.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.AdminApiKeyRow;

import java.time.LocalDateTime;

@Mapper
public interface AdminApiKeyMapper {

    @Select("SELECT * FROM admin_api_keys WHERE key_prefix = #{prefix}")
    AdminApiKeyRow findByPrefix(@Param("prefix") String prefix);

    @Select("SELECT * FROM admin_api_keys WHERE id = #{id}")
    AdminApiKeyRow findById(@Param("id") long id);

    @Update("UPDATE admin_api_keys SET last_used_at = #{at} WHERE id = #{id}")
    int touchLastUsed(@Param("id") long id, @Param("at") LocalDateTime at);
}
