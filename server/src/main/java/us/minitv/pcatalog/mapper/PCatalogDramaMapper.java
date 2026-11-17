package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaRow;

import java.util.List;

/** Public read path over {@code dramas}; SQL lives in mapper/pcatalog_drama.xml. */
@Mapper
public interface PCatalogDramaMapper {

    DramaRow findById(@Param("id") long id);

    /** All published dramas, most recently updated first (content.list_published_dramas). */
    List<DramaRow> listPublished();

    /** Published dramas in id order - feed recall, whose Python query had no ORDER BY. */
    List<DramaRow> listVisiblePublished();

    /** Published dramas whose title contains q, most recently updated first. */
    List<DramaRow> searchPublished(@Param("q") String q, @Param("limit") int limit);

    List<DramaRow> findPublishedByIds(@Param("ids") List<Long> ids);
}
