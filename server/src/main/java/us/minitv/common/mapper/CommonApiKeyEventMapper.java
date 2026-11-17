package us.minitv.common.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommonApiKeyEventMapper {

    @Insert("""
            INSERT INTO api_key_events (key_id, staff_id, method, path, status_code, duration_ms, created_at)
            VALUES (#{keyId}, #{staffId}, #{method}, #{path}, #{statusCode}, #{durationMs}, NOW())
            """)
    int insert(@Param("keyId") long keyId, @Param("staffId") long staffId,
               @Param("method") String method, @Param("path") String path,
               @Param("statusCode") int statusCode, @Param("durationMs") Integer durationMs);
}
