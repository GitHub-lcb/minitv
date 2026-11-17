package us.minitv.paccount;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.account.row.VerificationCodeRow;

/** verification_codes access (only the hash is ever stored). */
@Mapper
public interface PAccountVerificationCodeMapper {

    @Select("SELECT * FROM verification_codes WHERE email = #{email} AND purpose = #{purpose} "
            + "ORDER BY id DESC LIMIT 1")
    VerificationCodeRow findLatest(@Param("email") String email, @Param("purpose") String purpose);

    @Insert("INSERT INTO verification_codes (email, purpose, code_hash, attempts, expires_at) "
            + "VALUES (#{email}, #{purpose}, #{codeHash}, #{attempts}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(VerificationCodeRow row);

    @Update("UPDATE verification_codes SET attempts = attempts + 1 WHERE id = #{id}")
    int incrementAttempts(@Param("id") long id);

    @Delete("DELETE FROM verification_codes WHERE id = #{id}")
    int deleteById(@Param("id") long id);
}
