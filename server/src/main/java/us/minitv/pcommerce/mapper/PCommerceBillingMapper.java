package us.minitv.pcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.OrderRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.billing.row.SubscriptionRow;
import us.minitv.pcommerce.dto.PCommerceOrderView;

import java.time.LocalDateTime;
import java.util.List;

/** plans / plan_versions / subscriptions / orders / billing_settings. */
@Mapper
public interface PCommerceBillingMapper {

    @Select("SELECT * FROM plans WHERE status = 'active' ORDER BY id")
    List<PlanRow> activePlans();

    @Select("SELECT * FROM plans WHERE code = #{code} LIMIT 1")
    PlanRow findPlanByCode(@Param("code") String code);

    @Select("SELECT * FROM plans WHERE id = #{id}")
    PlanRow findPlanById(@Param("id") long id);

    @Select("SELECT * FROM plan_versions WHERE plan_id = #{planId} ORDER BY version DESC LIMIT 1")
    PlanVersionRow latestVersion(@Param("planId") long planId);

    @Select("SELECT * FROM plan_versions WHERE id = #{id}")
    PlanVersionRow findVersion(@Param("id") long id);

    @Select("SELECT id FROM plan_versions WHERE stripe_price_id = #{priceId} LIMIT 1")
    Long findVersionIdByStripePrice(@Param("priceId") String priceId);

    @Update("UPDATE plan_versions SET stripe_price_id = #{priceId} WHERE id = #{id}")
    int setVersionStripePrice(@Param("id") long id, @Param("priceId") String priceId);

    @Update("UPDATE plan_versions SET stripe_one_time_price_id = #{priceId} WHERE id = #{id}")
    int setVersionStripeOneTimePrice(@Param("id") long id, @Param("priceId") String priceId);

    @Select("SELECT * FROM billing_settings WHERE id = 1")
    BillingSettingRow findBillingSettings();

    @Insert("INSERT INTO billing_settings (id, billing_mode) VALUES (1, #{billingMode})")
    int insertBillingSettings(@Param("billingMode") String billingMode);

    @Select("SELECT o.order_no, p.code AS plan_code, p.name_en AS plan_name_en, p.name_zh AS plan_name_zh, "
            + "o.amount_cents, o.currency, o.billing_mode, o.status, o.paid_at, o.created_at "
            + "FROM orders o "
            + "JOIN plan_versions pv ON pv.id = o.plan_version_id "
            + "JOIN plans p ON p.id = pv.plan_id "
            + "WHERE o.user_id = #{userId} AND o.status IN ('paid', 'refunded') "
            + "ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<PCommerceOrderView> listUserOrders(@Param("userId") long userId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    @Select("SELECT * FROM orders WHERE external_txn_id = #{txn} LIMIT 1")
    OrderRow findOrderByExternalTxn(@Param("txn") String txn);

    @Select("SELECT * FROM orders WHERE id = #{id}")
    OrderRow findOrderById(@Param("id") long id);

    @Insert("INSERT INTO orders (order_no, user_id, plan_version_id, amount_cents, currency, billing_mode, "
            + "status, external_txn_id, paid_at, source_drama_id, refunded_at) VALUES ("
            + "#{orderNo}, #{userId}, #{planVersionId}, #{amountCents}, #{currency}, #{billingMode}, "
            + "#{status}, #{externalTxnId,jdbcType=VARCHAR}, #{paidAt,jdbcType=TIMESTAMP}, "
            + "#{sourceDramaId,jdbcType=BIGINT}, #{refundedAt,jdbcType=TIMESTAMP})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrder(OrderRow row);

    @Update("UPDATE orders SET status = #{status}, paid_at = #{paidAt}, updated_at = now() WHERE id = #{id}")
    int markOrderPaid(@Param("id") long id,
                      @Param("status") String status,
                      @Param("paidAt") LocalDateTime paidAt);

    @Update("UPDATE orders SET billing_mode = #{billingMode}, updated_at = now() WHERE id = #{id}")
    int setOrderBillingMode(@Param("id") long id, @Param("billingMode") String billingMode);

    @Update("UPDATE orders SET source_drama_id = #{sourceDramaId}, updated_at = now() "
            + "WHERE id = #{id} AND source_drama_id IS NULL")
    int setOrderSourceDramaIfNull(@Param("id") long id, @Param("sourceDramaId") long sourceDramaId);

    @Select("SELECT * FROM subscriptions WHERE user_id = #{userId} AND status = 'active' LIMIT 1")
    SubscriptionRow findActiveSubscription(@Param("userId") long userId);

    @Select("SELECT * FROM subscriptions WHERE stripe_subscription_id = #{stripeSubscriptionId} LIMIT 1")
    SubscriptionRow findSubscriptionByStripeId(@Param("stripeSubscriptionId") String stripeSubscriptionId);

    @Select("SELECT * FROM subscriptions WHERE user_id = #{userId} AND stripe_customer_id IS NOT NULL "
            + "ORDER BY id DESC LIMIT 1")
    SubscriptionRow findSubscriptionWithCustomer(@Param("userId") long userId);

    @Insert("INSERT INTO subscriptions (user_id, plan_id, plan_version_id, status, current_period_start, "
            + "current_period_end, canceled_at, stripe_customer_id, stripe_subscription_id, stripe_price_id, "
            + "billing_mode, pass_end, source_drama_id) VALUES ("
            + "#{userId}, #{planId}, #{planVersionId}, #{status}, #{currentPeriodStart}, #{currentPeriodEnd}, "
            + "#{canceledAt,jdbcType=TIMESTAMP}, #{stripeCustomerId,jdbcType=VARCHAR}, "
            + "#{stripeSubscriptionId,jdbcType=VARCHAR}, #{stripePriceId,jdbcType=VARCHAR}, "
            + "#{billingMode}, #{passEnd,jdbcType=TIMESTAMP}, #{sourceDramaId,jdbcType=BIGINT})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSubscription(SubscriptionRow row);

    @Update("UPDATE subscriptions SET plan_id = #{planId}, plan_version_id = #{planVersionId}, "
            + "status = #{status}, current_period_start = #{currentPeriodStart}, "
            + "current_period_end = #{currentPeriodEnd}, canceled_at = #{canceledAt,jdbcType=TIMESTAMP}, "
            + "stripe_customer_id = #{stripeCustomerId,jdbcType=VARCHAR}, "
            + "stripe_subscription_id = #{stripeSubscriptionId,jdbcType=VARCHAR}, "
            + "stripe_price_id = #{stripePriceId,jdbcType=VARCHAR}, billing_mode = #{billingMode}, "
            + "pass_end = #{passEnd,jdbcType=TIMESTAMP}, source_drama_id = #{sourceDramaId,jdbcType=BIGINT}, "
            + "updated_at = now() WHERE id = #{id}")
    int updateSubscription(SubscriptionRow row);
}
