package us.minitv.paccount;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.account.row.UserRow;

import java.time.LocalDate;

/**
 * users access for the public account flow. The base module already owns a
 * read-only UserMapper; the rewrite rules require cross-module SQL to live in
 * the consuming package, so this mapper carries the writes as well.
 */
@Mapper
public interface PAccountUserMapper {

    @Select("SELECT * FROM users WHERE id = #{id}")
    UserRow findById(@Param("id") long id);

    @Select("SELECT * FROM users WHERE email = #{email}")
    UserRow findByEmail(@Param("email") String email);

    @Insert("INSERT INTO users (email, display_name, avatar_url, gender, bio, birthday, region, locale, "
            + "public_id, following_count, follower_count, received_likes, reminder_opt_in) "
            + "VALUES (#{email}, #{displayName}, #{avatarUrl}, #{gender}, #{bio}, #{birthday}, #{region}, "
            + "#{locale}, #{publicId}, #{followingCount}, #{followerCount}, #{receivedLikes}, #{reminderOptIn})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserRow user);

    @Update("UPDATE users SET email = #{email}, updated_at = now() WHERE id = #{id}")
    int updateEmail(@Param("id") long id, @Param("email") String email);

    @Update("UPDATE users SET display_name = #{displayName}, updated_at = now() WHERE id = #{id}")
    int updateDisplayName(@Param("id") long id, @Param("displayName") String displayName);

    @Update("UPDATE users SET avatar_url = #{avatarUrl}, updated_at = now() WHERE id = #{id}")
    int updateAvatarUrl(@Param("id") long id, @Param("avatarUrl") String avatarUrl);

    @Update("UPDATE users SET reminder_opt_in = #{enabled}, updated_at = now() WHERE id = #{id}")
    int updateReminderOptIn(@Param("id") long id, @Param("enabled") boolean enabled);

    /**
     * PATCH /v1/me partial update: only the keys the client actually sent are
     * written (Pydantic's model_dump(exclude_none=True)); SQL lives in
     * mapper/paccount_user.xml.
     */
    int updateProfile(@Param("id") long id,
                      @Param("displayName") String displayName,
                      @Param("avatarUrl") String avatarUrl,
                      @Param("gender") String gender,
                      @Param("bio") String bio,
                      @Param("birthday") LocalDate birthday,
                      @Param("region") String region,
                      @Param("locale") String locale);
}
