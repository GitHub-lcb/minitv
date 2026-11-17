package us.minitv.paccount;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.account.row.ThirdPartyIdentitieRow;

/** third_party_identities access for Google / Facebook account linking. */
@Mapper
public interface PAccountThirdPartyIdentityMapper {

    @Select("SELECT * FROM third_party_identities WHERE provider = #{provider} "
            + "AND provider_user_id = #{subject} LIMIT 1")
    ThirdPartyIdentitieRow findByProviderAndSubject(@Param("provider") String provider,
                                                    @Param("subject") String subject);

    @Insert("INSERT INTO third_party_identities (user_id, provider, provider_user_id) "
            + "VALUES (#{userId}, #{provider}, #{subject})")
    int insert(@Param("userId") long userId,
               @Param("provider") String provider,
               @Param("subject") String subject);
}
