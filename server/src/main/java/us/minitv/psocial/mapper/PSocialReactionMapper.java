package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaRow;
import us.minitv.psocial.dto.PSocialCountRow;
import us.minitv.social.row.ReactionRow;

import java.util.List;

/** reactions table: toggle, per-comment like counts and the favorites/lists joins. */
@Mapper
public interface PSocialReactionMapper {

    ReactionRow findOne(@Param("userId") long userId,
                        @Param("targetType") String targetType,
                        @Param("targetId") long targetId,
                        @Param("kind") String kind);

    int insert(ReactionRow row);

    int deleteById(@Param("id") long id);

    List<PSocialCountRow> countByTarget(@Param("targetType") String targetType,
                                        @Param("kind") String kind,
                                        @Param("ids") List<Long> ids);

    List<Long> listTargetIds(@Param("userId") long userId,
                             @Param("targetType") String targetType,
                             @Param("kind") String kind,
                             @Param("ids") List<Long> ids);

    long countFavoriteDramas(@Param("userId") long userId);

    List<DramaRow> listFavoriteDramas(@Param("userId") long userId,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    List<ReactionRow> listByUserAndKind(@Param("userId") long userId, @Param("kind") String kind);
}
