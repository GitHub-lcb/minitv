package us.minitv.ops.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.AdminUserRow;

import java.time.LocalDateTime;

@Mapper
public interface AdminUserMapper {

    @Select("SELECT * FROM admin_users WHERE id = #{id}")
    AdminUserRow findById(@Param("id") long id);

    @Select("SELECT * FROM admin_users WHERE username = #{username}")
    AdminUserRow findByUsername(@Param("username") String username);

    @Update("UPDATE admin_users SET last_login_at = #{at} WHERE id = #{id}")
    int touchLastLogin(@Param("id") long id, @Param("at") LocalDateTime at);
}
