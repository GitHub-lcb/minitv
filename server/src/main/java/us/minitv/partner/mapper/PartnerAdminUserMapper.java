package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.AdminUserRow;

/** Staff-account reads/writes the partner surface needs beyond the shared mappers. */
@Mapper
public interface PartnerAdminUserMapper {

    @Select("SELECT * FROM admin_users WHERE id = #{id}")
    AdminUserRow findById(@Param("id") long id);

    @Update("UPDATE admin_users SET password_hash = #{passwordHash}, updated_at = NOW() WHERE id = #{id}")
    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);
}
