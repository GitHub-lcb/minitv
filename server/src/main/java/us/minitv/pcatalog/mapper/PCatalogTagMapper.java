package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.TagRow;
import us.minitv.pcatalog.dto.PCatalogDramaTag;

import java.util.List;

/** Public tag reads; SQL lives in mapper/pcatalog_tag.xml. */
@Mapper
public interface PCatalogTagMapper {

    /** Enabled tags, mains first (category asc), then tag id (content.tags_by_drama ordering). */
    List<TagRow> listEnabledOrdered();

    /** Enabled tags per drama, tag-id order. */
    List<PCatalogDramaTag> tagsByDramaIds(@Param("ids") List<Long> ids);
}
