package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.social.row.InboxMessageRow;

import java.time.LocalDateTime;
import java.util.List;

/** inbox_messages table: the Me page's activity list. */
@Mapper
public interface PSocialInboxMapper {

    List<InboxMessageRow> listByUser(@Param("userId") long userId);

    int markAllRead(@Param("userId") long userId, @Param("now") LocalDateTime now);
}
