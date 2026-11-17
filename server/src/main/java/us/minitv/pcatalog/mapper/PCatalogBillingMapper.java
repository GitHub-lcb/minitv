package us.minitv.pcatalog.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;

import java.util.List;

/** Plan catalogue reads used by /v1/marketing (billing.list_current_plans). */
@Mapper
public interface PCatalogBillingMapper {

    @Select("SELECT * FROM plans WHERE status = 'active' ORDER BY id")
    List<PlanRow> listActivePlans();

    /** Newest version of one plan; Python ordered by version desc and took the first. */
    @Select("SELECT * FROM plan_versions WHERE plan_id = #{planId} ORDER BY version DESC LIMIT 1")
    PlanVersionRow latestVersion(@Param("planId") long planId);

    @Select("SELECT * FROM billing_settings WHERE id = #{id}")
    BillingSettingRow findSettings(@Param("id") int id);

    @Insert("INSERT INTO billing_settings (id, billing_mode) VALUES (#{id}, #{mode})")
    int insertSettings(@Param("id") int id, @Param("mode") String mode);
}
