package us.minitv.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.account.row.UserRow;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE id = #{id}")
    UserRow findById(@Param("id") long id);

    @Select("SELECT * FROM users WHERE email = #{email}")
    UserRow findByEmail(@Param("email") String email);
}
