package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.ops.row.ChannelTabRow;
import us.minitv.ops.row.CopyEntrieRow;
import us.minitv.ops.row.HotSearcheRow;
import us.minitv.ops.row.LegalPageRow;
import us.minitv.ops.row.MarketingStatRow;
import us.minitv.ops.row.StoreLinkRow;
import us.minitv.ops.row.TestimonialRow;

import java.util.List;

/** Operational configuration tables read by /v1/config, /v1/marketing and friends. */
@Mapper
public interface PCatalogOpsConfigMapper {

    @Select("SELECT * FROM channel_tabs WHERE enabled = 1 ORDER BY position")
    List<ChannelTabRow> listEnabledTabs();

    @Select("SELECT * FROM copy_entries ORDER BY id")
    List<CopyEntrieRow> listCopies();

    @Select("SELECT * FROM hot_searches WHERE enabled = 1 ORDER BY position")
    List<HotSearcheRow> listEnabledHotSearches();

    @Select("SELECT slug, title, body FROM legal_pages WHERE slug = #{slug}")
    LegalPageRow findLegalPage(@Param("slug") String slug);

    @Select("SELECT * FROM marketing_stats ORDER BY position")
    List<MarketingStatRow> listMarketingStats();

    @Select("SELECT * FROM testimonials ORDER BY position")
    List<TestimonialRow> listTestimonials();

    @Select("SELECT * FROM store_links ORDER BY id")
    List<StoreLinkRow> listStoreLinks();
}
