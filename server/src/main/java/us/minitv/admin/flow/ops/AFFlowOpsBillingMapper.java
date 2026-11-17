package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.billing.row.SubscriptionRow;

import java.util.List;

/** Plans, plan versions, the global billing switch and subscriptions. */
@Mapper
public interface AFFlowOpsBillingMapper {

    @Select("SELECT * FROM plans WHERE status = 'active' ORDER BY id")
    List<PlanRow> listActivePlans();

    @Select("SELECT * FROM plans WHERE code = #{code}")
    PlanRow findPlanByCode(@Param("code") String code);

    @Select("SELECT * FROM plans WHERE code = #{code} AND status = 'active'")
    PlanRow findActivePlanByCode(@Param("code") String code);

    @Select("SELECT * FROM plans WHERE id = #{id}")
    PlanRow findPlanById(@Param("id") long id);

    @Select("SELECT * FROM plan_versions WHERE plan_id = #{planId} ORDER BY version DESC LIMIT 1")
    PlanVersionRow findLatestVersion(@Param("planId") long planId);

    @Select("SELECT * FROM plan_versions WHERE id = #{id}")
    PlanVersionRow findVersionById(@Param("id") long id);

    @Insert("INSERT INTO plan_versions (plan_id, version, price_cents, currency, benefits, note) "
            + "VALUES (#{planId}, #{version}, #{priceCents}, #{currency}, #{benefits}, #{note})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlanVersion(PlanVersionRow row);

    @Update("UPDATE plan_versions SET stripe_price_id = #{priceId} WHERE id = #{id}")
    int updateStripePriceId(@Param("id") long id, @Param("priceId") String priceId);

    @Update("UPDATE plan_versions SET stripe_one_time_price_id = #{priceId} WHERE id = #{id}")
    int updateStripeOneTimePriceId(@Param("id") long id, @Param("priceId") String priceId);

    @Select("SELECT * FROM billing_settings WHERE id = 1")
    BillingSettingRow findBillingSettings();

    @Insert("INSERT INTO billing_settings (id, billing_mode) VALUES (1, #{mode})")
    int insertBillingSettings(@Param("mode") String mode);

    @Update("UPDATE billing_settings SET billing_mode = #{mode}, updated_by = #{actorId}, "
            + "updated_at = NOW() WHERE id = 1")
    int updateBillingSettings(@Param("mode") String mode, @Param("actorId") long actorId);

    @Select("SELECT * FROM subscriptions WHERE user_id = #{userId} AND status = 'active' LIMIT 1")
    SubscriptionRow findActiveSubscription(@Param("userId") long userId);

    @Select("SELECT * FROM subscriptions WHERE user_id = #{userId} AND status = 'active' "
            + "LIMIT 1 FOR UPDATE")
    SubscriptionRow lockActiveSubscription(@Param("userId") long userId);

    @Insert("INSERT INTO subscriptions (user_id, plan_id, plan_version_id, status, "
            + "current_period_start, current_period_end, billing_mode) "
            + "VALUES (#{userId}, #{planId}, #{planVersionId}, #{status}, "
            + "#{currentPeriodStart}, #{currentPeriodEnd}, #{billingMode})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSubscription(SubscriptionRow row);

    @Update("UPDATE subscriptions SET pass_end = #{passEnd}, plan_id = #{planId}, "
            + "plan_version_id = #{planVersionId}, updated_at = NOW() WHERE id = #{id}")
    int updateSubscriptionGrant(SubscriptionRow row);

    @Update("UPDATE subscriptions SET pass_end = #{passEnd}, current_period_end = #{currentPeriodEnd}, "
            + "canceled_at = #{canceledAt}, status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateSubscriptionRevoke(SubscriptionRow row);

    @Update("UPDATE subscriptions SET canceled_at = #{canceledAt}, updated_at = NOW() WHERE id = #{id}")
    int updateSubscriptionCanceledAt(SubscriptionRow row);

    @Select("SELECT * FROM subscriptions WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<SubscriptionRow> listSubscriptions(@Param("userId") long userId, @Param("limit") int limit);
}
