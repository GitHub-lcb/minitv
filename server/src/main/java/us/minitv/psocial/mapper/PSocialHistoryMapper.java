package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.psocial.dto.PSocialHistoryView;

import java.util.List;

/**
 * Watch history: playback_progress joined to episodes and published dramas.
 * Written from this package because the playback module's own mappers belong to
 * another agent.
 */
@Mapper
public interface PSocialHistoryMapper {

    List<PSocialHistoryView> listRecent(@Param("userId") long userId, @Param("limit") int limit);
}
