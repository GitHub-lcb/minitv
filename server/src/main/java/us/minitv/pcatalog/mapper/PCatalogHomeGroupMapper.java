package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.content.row.DramaHomeGroupRow;
import us.minitv.content.row.HomeGroupRow;
import us.minitv.ops.row.BannerRow;

import java.util.List;

/** Home groups and banners; SQL lives in mapper/pcatalog_homegroup.xml. */
@Mapper
public interface PCatalogHomeGroupMapper {

    List<HomeGroupRow> listActiveGroups();

    List<DramaHomeGroupRow> linksByGroupIds(@Param("groupIds") List<Long> groupIds);

    List<BannerRow> listOnlineBanners();
}
