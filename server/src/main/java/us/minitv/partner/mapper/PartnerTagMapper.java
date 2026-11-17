package us.minitv.partner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.TagRow;

import java.util.List;

/** Read-only tag lookups for the supplier taxonomy picker. */
@Mapper
public interface PartnerTagMapper {

    List<TagRow> listEnabled();

    List<TagRow> findByIds(@Param("ids") List<Long> ids);
}
