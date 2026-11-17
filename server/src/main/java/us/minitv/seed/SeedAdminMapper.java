package us.minitv.seed;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.ops.row.AdminUserRow;

@Mapper
public interface SeedAdminMapper {

    @Select("SELECT COUNT(*) FROM admin_users")
    long countAdmins();

    @Select("SELECT id, username, role, status FROM admin_users WHERE username = #{username}")
    AdminUserRow findByUsername(@Param("username") String username);

    @Insert("""
            INSERT INTO admin_users (username, password_hash, display_name, role, status, created_at, updated_at)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, 'active', NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminUserRow row);
}
