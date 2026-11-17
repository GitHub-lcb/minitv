package us.minitv.admin.flow.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaRow;

import java.time.LocalDateTime;
import java.util.List;

/** Drama reads/writes used by the admin flow module (table {@code dramas}). */
@Mapper
public interface AFContentDramaMapper {

    DramaRow findById(@Param("id") long id);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int updatePublishedAt(@Param("id") long id, @Param("publishedAt") LocalDateTime publishedAt);

    int updateCoverUrl(@Param("id") long id, @Param("coverUrl") String coverUrl);

    int updateSchedule(@Param("id") long id, @Param("scheduledAt") LocalDateTime scheduledAt);

    List<DramaRow> listDueSchedules(@Param("now") LocalDateTime now);

    int deleteById(@Param("id") long id);
}
