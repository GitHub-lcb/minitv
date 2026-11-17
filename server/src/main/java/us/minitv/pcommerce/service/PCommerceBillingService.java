package us.minitv.pcommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.OrderRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.billing.row.SubscriptionRow;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.pcommerce.dto.PCommerceOrderView;
import us.minitv.pcommerce.mapper.PCommerceBillingMapper;
import us.minitv.pcommerce.support.PCommerceCrypto;
import us.minitv.pcommerce.support.PCommerceEnv;
import us.minitv.pcommerce.support.PCommerceRequests;
import us.minitv.pcommerce.support.PCommerceStripeClient;
import us.minitv.security.Tokens;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan catalog, order history, Stripe Checkout and webhooks, ported from
 * {@code app/modules/billing/service.py}.
 *
 * <p>Stripe owns the money; every webhook handler re-applies a full snapshot so
 * retries are idempotent. The local subscription row is the source of truth for
 * access windows ({@code max(current_period_end, pass_end)}).</p>
 */
@Service
public class PCommerceBillingService {

    private static final Map<String, String> PRICE_ENV = new LinkedHashMap<>();
    private static final Map<String, String> PRODUCT_ENV = new LinkedHashMap<>();
    private static final Map<String, String> INTERVAL_OF = new LinkedHashMap<>();
    private static final Map<String, Integer> PLAN_DAYS = new LinkedHashMap<>();
    private static final Set<String> BILLING_MODES = Set.of("subscription", "one_time");
    private static final int MIN_ONE_TIME_CENTS = 50;

    static {
        PRICE_ENV.put("weekly", "STRIPE_PRICE_WEEKLY");
        PRICE_ENV.put("monthly", "STRIPE_PRICE_MONTHLY");
        PRICE_ENV.put("yearly", "STRIPE_PRICE_YEARLY");
        PRODUCT_ENV.put("weekly", "STRIPE_PRODUCT_WEEKLY");
        PRODUCT_ENV.put("monthly", "STRIPE_PRODUCT_MONTHLY");
        PRODUCT_ENV.put("yearly", "STRIPE_PRODUCT_YEARLY");
        INTERVAL_OF.put("weekly", "week");
        INTERVAL_OF.put("monthly", "month");
        INTERVAL_OF.put("yearly", "year");
        PLAN_DAYS.put("weekly", 7);
        PLAN_DAYS.put("monthly", 30);
        PLAN_DAYS.put("yearly", 365);
    }

    private final PCommerceBillingMapper billing;
    private final PCommerceContentService content;
    private final PCommerceStripeClient stripe;
    private final PCommerceEnv env;

    public PCommerceBillingService(PCommerceBillingMapper billing,
                                   PCommerceContentService content,
                                   PCommerceStripeClient stripe,
                                   PCommerceEnv env) {
        this.billing = billing;
        this.content = content;
        this.stripe = stripe;
        this.env = env;
    }

    // ------------------------------------------------------------- plan catalog

    /** billing.list_current_plans: active plans pinned to their latest version. */
    public List<Map<String, Object>> listCurrentPlans() {
        List<Map<String, Object>> out = new ArrayList<>();
        String mode = billingMode();
        for (PlanRow plan : billing.activePlans()) {
            PlanVersionRow version = billing.latestVersion(plan.getId());
            if (version == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", plan.getCode());
            item.put("name_en", plan.getNameEn());
            item.put("name_zh", plan.getNameZh());
            item.put("price_cents", version.getPriceCents());
            item.put("currency", version.getCurrency());
            item.put("version", version.getVersion());
            item.put("version_id", version.getId());
            item.put("benefits", Json.readMap(version.getBenefits()));
            item.put("billing_mode", mode);
            out.add(item);
        }
        return out;
    }

    /** billing.get_billing_settings: single row, created on first read as one_time. */
    public String billingMode() {
        BillingSettingRow row = billing.findBillingSettings();
        if (row == null) {
            billing.insertBillingSettings("one_time");
            return "one_time";
        }
        return row.getBillingMode();
    }

    /** billing.list_user_orders: only money that actually moved, newest first. */
    public Map<String, Object> listUserOrders(long userId, int limit, int offset) {
        List<PCommerceOrderView> rows = billing.listUserOrders(userId, limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        int size = Math.min(rows.size(), limit);
        for (int index = 0; index < size; index++) {
            PCommerceOrderView row = rows.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order_no", row.getOrderNo());
            item.put("plan_code", row.getPlanCode());
            item.put("plan_name_en", row.getPlanNameEn());
            item.put("plan_name_zh", row.getPlanNameZh());
            item.put("amount_cents", row.getAmountCents());
            item.put("currency", row.getCurrency());
            item.put("billing_mode", row.getBillingMode());
            item.put("status", row.getStatus());
            item.put("paid_at", row.getPaidAt());
            item.put("created_at", row.getCreatedAt());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("has_more", hasMore);
        return out;
    }

    /** billing.access_until: latest live moment of subscription period or pass. */
    public LocalDateTime accessUntil(long userId) {
        SubscriptionRow row = billing.findActiveSubscription(userId);
        if (row == null) {
            return null;
        }
        LocalDateTime now = Times.now();
        LocalDateTime best = null;
        for (LocalDateTime end : new LocalDateTime[]{row.getCurrentPeriodEnd(), row.getPassEnd()}) {
            if (end != null && end.isAfter(now) && (best == null || end.isAfter(best))) {
                best = end;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- cancel

    /** billing.cancel_subscription: stop renewing; access runs to period end. */
    @Transactional
    public void cancelSubscription(long userId) {
        SubscriptionRow current = billing.findActiveSubscription(userId);
        if (current == null) {
            return;
        }
        boolean hasStripe = current.getStripeSubscriptionId() != null
                && !current.getStripeSubscriptionId().isEmpty();
        if (!hasStripe && "one_time".equals(current.getBillingMode())) {
            throw new ApiException(400, "one-time passes cannot be canceled");
        }
        if (hasStripe) {
            Map<String, String> data = PCommerceStripeClient.form();
            data.put("cancel_at_period_end", "true");
            stripe.request("/subscriptions/" + current.getStripeSubscriptionId(), data, "POST");
            current.setCanceledAt(Times.now());
            billing.updateSubscription(current);
            return;
        }
        current.setStatus("canceled");
        current.setCanceledAt(Times.now());
        billing.updateSubscription(current);
    }

    // ------------------------------------------------------------- legacy webhook

    /** billing.apply_webhook: HMAC-signed local fake webhook (X-Pay-Signature). */
    @Transactional
    public Map<String, Object> applyWebhook(byte[] body, String signature, JsonNode payload) {
        String expected = PCommerceCrypto.hmacSha256Hex(env.payWebhookSecret(), body);
        if (!PCommerceCrypto.constantTimeEquals(expected, signature)) {
            throw new ApiException(401, "bad signature");
        }
        String txn = requiredText(payload, "txn_id");
        OrderRow existing = billing.findOrderByExternalTxn(txn);
        if (existing != null) {
            Map<String, Object> replayed = new LinkedHashMap<>();
            replayed.put("order_no", existing.getOrderNo());
            replayed.put("replayed", true);
            replayed.put("status", existing.getStatus());
            return replayed;
        }
        String planCode = requiredText(payload, "plan_code");
        PlanRow plan = billing.findPlanByCode(planCode);
        if (plan == null) {
            throw new ApiException(404, "plan not found");
        }
        PlanVersionRow version = latestVersion(plan);
        long userId = requiredLong(payload, "user_id");
        LocalDateTime now = Times.now();
        int days = PLAN_DAYS.getOrDefault(plan.getCode(), 30);
        OrderRow order = new OrderRow();
        order.setOrderNo(Tokens.hex(8));
        order.setUserId(userId);
        order.setPlanVersionId(version.getId());
        order.setAmountCents(version.getPriceCents());
        order.setCurrency("USD");
        order.setBillingMode("subscription");
        order.setStatus("paid");
        order.setExternalTxnId(txn);
        order.setPaidAt(now);
        billing.insertOrder(order);

        SubscriptionRow current = billing.findActiveSubscription(userId);
        if (current != null) {
            current.setStatus("canceled");
            current.setCanceledAt(now);
            billing.updateSubscription(current);
        }
        SubscriptionRow subscription = new SubscriptionRow();
        subscription.setUserId(userId);
        subscription.setPlanId(plan.getId());
        subscription.setPlanVersionId(version.getId());
        subscription.setStatus("active");
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(now.plusDays(days));
        subscription.setBillingMode("subscription");
        billing.insertSubscription(subscription);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_no", order.getOrderNo());
        out.put("replayed", false);
        out.put("status", "paid");
        out.put("plan_version_id", version.getId());
        return out;
    }

    // ------------------------------------------------------------- checkout

    /** billing.create_checkout_session: open a Stripe Checkout and store the order. */
    public Map<String, Object> createCheckoutSession(long userId, String email, String planCode,
                                                    String successUrl, String cancelUrl,
                                                    Long sourceDramaId) {
        PlanRow plan = billing.findPlanByCode(planCode);
        if (plan == null || !"active".equals(plan.getStatus())) {
            throw new ApiException(404, "plan not found");
        }
        PlanVersionRow version = latestVersion(plan);
        if (sourceDramaId != null) {
            DramaRow drama = content.findDrama(sourceDramaId);
            if (drama == null || !"published".equals(drama.getStatus())) {
                throw new ApiException(404, "source drama not found");
            }
        }
        String mode = billingMode();
        ensureChargeable(mode, version.getPriceCents(), version.getCurrency());
        String base = env.h5Base();
        Map<String, String> data = PCommerceStripeClient.form();
        data.put("mode", "one_time".equals(mode) ? "payment" : "subscription");
        data.put("line_items[0][price]", ensureStripePrice(plan, version, mode));
        data.put("line_items[0][quantity]", "1");
        data.put("success_url", successUrl != null && !successUrl.isEmpty()
                ? successUrl : base + "/?checkout=success");
        data.put("cancel_url", cancelUrl != null && !cancelUrl.isEmpty()
                ? cancelUrl : base + "/?checkout=cancel");
        data.put("client_reference_id", String.valueOf(userId));
        data.put("allow_promotion_codes", "true");
        data.put("metadata[user_id]", String.valueOf(userId));
        data.put("metadata[plan_code]", planCode);
        data.put("metadata[billing_mode]", mode);
        if (sourceDramaId != null) {
            data.put("metadata[source_drama_id]", String.valueOf(sourceDramaId));
        }
        if ("one_time".equals(mode)) {
            data.put("payment_intent_data[metadata][user_id]", String.valueOf(userId));
            data.put("payment_intent_data[metadata][plan_code]", planCode);
            if (sourceDramaId != null) {
                data.put("payment_intent_data[metadata][source_drama_id]", String.valueOf(sourceDramaId));
            }
        } else {
            data.put("subscription_data[metadata][user_id]", String.valueOf(userId));
            data.put("subscription_data[metadata][plan_code]", planCode);
            if (sourceDramaId != null) {
                data.put("subscription_data[metadata][source_drama_id]", String.valueOf(sourceDramaId));
            }
        }
        SubscriptionRow existing = billing.findSubscriptionWithCustomer(userId);
        String customerId = existing == null ? null : existing.getStripeCustomerId();
        if ("one_time".equals(mode)) {
            if (customerId != null && !customerId.isEmpty()) {
                data.put("customer", customerId);
            } else {
                data.put("customer_creation", "always");
                if (email != null && !email.isEmpty()) {
                    data.put("customer_email", email);
                }
            }
        } else if (email != null && !email.isEmpty()) {
            data.put("customer_email", email);
        }
        JsonNode checkout = stripe.request("/checkout/sessions", data, "POST");
        String sessionId = PCommerceRequests.text(checkout, "id");
        String url = PCommerceRequests.text(checkout, "url");
        if (sessionId == null || url == null) {
            throw new IllegalStateException("stripe checkout response is missing id/url");
        }
        OrderRow order = new OrderRow();
        order.setOrderNo(Tokens.hex(8));
        order.setUserId(userId);
        order.setPlanVersionId(version.getId());
        order.setAmountCents(version.getPriceCents());
        order.setCurrency(version.getCurrency());
        order.setBillingMode(mode);
        order.setStatus("created");
        order.setExternalTxnId(sessionId);
        order.setSourceDramaId(sourceDramaId);
        billing.insertOrder(order);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("session_id", sessionId);
        out.put("order_no", order.getOrderNo());
        return out;
    }

    // ------------------------------------------------------------- stripe webhook

    /** billing.apply_stripe_event. */
    @Transactional
    public Map<String, Object> applyStripeEvent(byte[] body, String signature, JsonNode payload) {
        stripe.verifySignature(body, signature);
        String eventType = PCommerceRequests.text(payload, "type");
        if (eventType == null) {
            eventType = "";
        }
        JsonNode data = PCommerceRequests.field(payload, "data");
        JsonNode object = data == null ? null : PCommerceRequests.field(data, "object");
        Map<String, Object> result;
        if (Set.of("checkout.session.completed", "checkout.session.async_payment_succeeded")
                .contains(eventType)) {
            String mode = PCommerceRequests.text(object, "mode");
            if ("payment".equals(mode)) {
                result = activatePass(object);
            } else {
                result = activateFromCheckout(object);
            }
        } else if (Set.of("customer.subscription.created", "customer.subscription.updated")
                .contains(eventType)) {
            result = syncFromSubscription(object);
        } else if ("customer.subscription.deleted".equals(eventType)) {
            String stripeId = PCommerceRequests.text(object, "id");
            SubscriptionRow row = stripeId == null ? null : billing.findSubscriptionByStripeId(stripeId);
            if (row != null) {
                LocalDateTime now = Times.now();
                row.setCanceledAt(now);
                LocalDateTime passEnd = row.getPassEnd();
                boolean stillPaid = passEnd != null && passEnd.isAfter(now);
                row.setStatus(stillPaid ? "active" : "canceled");
                billing.updateSubscription(row);
            }
            result = new LinkedHashMap<>();
            result.put("canceled", row != null && "canceled".equals(row.getStatus()));
        } else {
            result = new LinkedHashMap<>();
            result.put("ignored", eventType);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        out.put("type", eventType);
        out.putAll(result);
        return out;
    }

    // ------------------------------------------------------------- stripe internals

    private Map<String, Object> activateFromCheckout(JsonNode checkout) {
        JsonNode metadata = PCommerceRequests.field(checkout, "metadata");
        Long userId = metadata == null ? null : PCommerceRequests.longValue(metadata, "user_id");
        if (userId == null) {
            userId = PCommerceRequests.longValue(checkout, "client_reference_id");
        }
        if (userId == null) {
            userId = 0L;
        }
        String planCode = metadata == null ? null : PCommerceRequests.text(metadata, "plan_code");
        Long sourceDramaId = sourceDramaId(metadata);
        if (userId == 0L || planCode == null || planCode.isEmpty()) {
            throw new ApiException(400, "checkout session is missing our metadata");
        }
        String checkoutId = PCommerceRequests.text(checkout, "id");
        OrderRow order = billing.findOrderByExternalTxn(checkoutId);
        if (order == null) {
            PlanRow plan = billing.findPlanByCode(planCode);
            if (plan == null) {
                throw new ApiException(404, "plan not found");
            }
            order = new OrderRow();
            order.setOrderNo(Tokens.hex(8));
            order.setUserId(userId);
            order.setPlanVersionId(latestVersion(plan).getId());
            Long amountTotal = PCommerceRequests.longValue(checkout, "amount_total");
            order.setAmountCents((int) (amountTotal == null ? 0L : amountTotal));
            String currency = PCommerceRequests.text(checkout, "currency");
            order.setCurrency((currency == null ? "usd" : currency).toUpperCase(Locale.ROOT));
            order.setBillingMode("subscription");
            order.setStatus("created");
            order.setExternalTxnId(checkoutId);
            order.setSourceDramaId(sourceDramaId);
            billing.insertOrder(order);
        } else if (order.getSourceDramaId() == null && sourceDramaId != null) {
            order.setSourceDramaId(sourceDramaId);
            billing.setOrderSourceDramaIfNull(order.getId(), sourceDramaId);
        }
        LocalDateTime paidAt = Times.now();
        order.setStatus("paid");
        order.setPaidAt(paidAt);
        billing.markOrderPaid(order.getId(), "paid", paidAt);

        String subscriptionId = PCommerceRequests.text(checkout, "subscription");
        String customerId = PCommerceRequests.text(checkout, "customer");
        String priceId = null;
        LocalDateTime periodStart = Times.now();
        LocalDateTime periodEnd = periodStart.plusDays(planDays(planCode));
        if (subscriptionId != null && !subscriptionId.isEmpty()) {
            JsonNode remote = stripe.request("/subscriptions/" + subscriptionId, null, "POST");
            JsonNode item = firstItem(remote);
            priceId = item == null ? null : PCommerceRequests.text(PCommerceRequests.field(item, "price"), "id");
            String remoteCustomer = PCommerceRequests.text(remote, "customer");
            if (remoteCustomer != null) {
                customerId = remoteCustomer;
            }
            JsonNode remoteMetadata = PCommerceRequests.field(remote, "metadata");
            String remotePlan = remoteMetadata == null
                    ? null : PCommerceRequests.text(remoteMetadata, "plan_code");
            if (remotePlan != null && !remotePlan.isEmpty()) {
                planCode = remotePlan;
            }
            LocalDateTime[] bounds = periodBounds(remote, planCode);
            periodStart = bounds[0];
            periodEnd = bounds[1];
        }
        upsertSubscription(userId, planCode, subscriptionId, customerId, priceId,
                "active", periodStart, periodEnd, sourceDramaId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_no", order.getOrderNo());
        out.put("status", order.getStatus());
        out.put("user_id", userId);
        return out;
    }

    /** billing._activate_pass: one-time purchase stacks days onto what is left. */
    private Map<String, Object> activatePass(JsonNode checkout) {
        JsonNode metadata = PCommerceRequests.field(checkout, "metadata");
        Long userId = metadata == null ? null : PCommerceRequests.longValue(metadata, "user_id");
        if (userId == null) {
            userId = PCommerceRequests.longValue(checkout, "client_reference_id");
        }
        if (userId == null) {
            userId = 0L;
        }
        String planCode = metadata == null ? null : PCommerceRequests.text(metadata, "plan_code");
        Long sourceDramaId = sourceDramaId(metadata);
        if (userId == 0L || planCode == null || planCode.isEmpty()) {
            throw new ApiException(400, "checkout session is missing our metadata");
        }
        String paymentStatus = PCommerceRequests.text(checkout, "payment_status");
        if (paymentStatus != null && !"paid".equals(paymentStatus)) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("pending", true);
            pending.put("session_id", PCommerceRequests.text(checkout, "id"));
            return pending;
        }
        String checkoutId = PCommerceRequests.text(checkout, "id");
        OrderRow order = billing.findOrderByExternalTxn(checkoutId);
        if (order != null && "paid".equals(order.getStatus())) {
            Map<String, Object> replayed = new LinkedHashMap<>();
            replayed.put("replayed", true);
            replayed.put("order_no", order.getOrderNo());
            return replayed;
        }
        PlanRow plan = billing.findPlanByCode(planCode);
        if (plan == null || !"active".equals(plan.getStatus())) {
            throw new ApiException(404, "plan not found");
        }
        PlanVersionRow version = latestVersion(plan);
        Long amountTotal = PCommerceRequests.longValue(checkout, "amount_total");
        String currency = PCommerceRequests.text(checkout, "currency");
        if (order == null) {
            order = new OrderRow();
            order.setOrderNo(Tokens.hex(8));
            order.setUserId(userId);
            order.setPlanVersionId(version.getId());
            order.setAmountCents((int) (amountTotal == null || amountTotal == 0L
                    ? version.getPriceCents() : amountTotal));
            order.setCurrency((currency == null || currency.isEmpty()
                    ? (version.getCurrency() == null ? "usd" : version.getCurrency()) : currency)
                    .toUpperCase(Locale.ROOT));
            order.setBillingMode("one_time");
            order.setStatus("created");
            order.setExternalTxnId(checkoutId);
            order.setSourceDramaId(sourceDramaId);
            billing.insertOrder(order);
        } else if (order.getSourceDramaId() == null && sourceDramaId != null) {
            order.setSourceDramaId(sourceDramaId);
            billing.setOrderSourceDramaIfNull(order.getId(), sourceDramaId);
        }
        LocalDateTime paidAt = Times.now();
        order.setStatus("paid");
        order.setPaidAt(paidAt);
        order.setBillingMode("one_time");
        billing.markOrderPaid(order.getId(), "paid", paidAt);
        billing.setOrderBillingMode(order.getId(), "one_time");

        LocalDateTime now = Times.now();
        int days = PLAN_DAYS.getOrDefault(planCode, 30);
        SubscriptionRow row = billing.findActiveSubscription(userId);
        if (row == null) {
            row = new SubscriptionRow();
            row.setUserId(userId);
            row.setPlanId(plan.getId());
            row.setPlanVersionId(version.getId());
            row.setStatus("active");
            row.setCurrentPeriodStart(now);
            row.setCurrentPeriodEnd(now);
            row.setBillingMode("one_time");
            billing.insertSubscription(row);
        }
        LocalDateTime base = now;
        for (LocalDateTime end : new LocalDateTime[]{row.getPassEnd(), row.getCurrentPeriodEnd()}) {
            if (end != null && end.isAfter(base)) {
                base = end;
            }
        }
        row.setPassEnd(base.plusDays(days));
        row.setBillingMode("one_time");
        row.setPlanId(plan.getId());
        row.setPlanVersionId(version.getId());
        if (sourceDramaId != null && row.getSourceDramaId() == null) {
            row.setSourceDramaId(sourceDramaId);
        }
        billing.updateSubscription(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_no", order.getOrderNo());
        out.put("status", "paid");
        out.put("user_id", userId);
        out.put("days", days);
        out.put("pass_until", row.getPassEnd());
        return out;
    }

    /** billing._sync_from_subscription: mirror a Stripe subscription snapshot. */
    private Map<String, Object> syncFromSubscription(JsonNode remote) {
        JsonNode metadata = PCommerceRequests.field(remote, "metadata");
        Long userId = metadata == null ? null : PCommerceRequests.longValue(metadata, "user_id");
        if (userId == null) {
            userId = 0L;
        }
        Long sourceDramaId = sourceDramaId(metadata);
        JsonNode item = firstItem(remote);
        String priceId = item == null ? null
                : PCommerceRequests.text(PCommerceRequests.field(item, "price"), "id");
        String planCode = metadata == null ? null : PCommerceRequests.text(metadata, "plan_code");
        if (planCode == null || planCode.isEmpty()) {
            planCode = planCodeForPrice(priceId);
        }
        String remoteId = PCommerceRequests.text(remote, "id");
        SubscriptionRow existing = remoteId == null ? null : billing.findSubscriptionByStripeId(remoteId);
        if (existing != null) {
            userId = existing.getUserId();
        }
        if (userId == 0L) {
            throw new ApiException(400, "subscription is missing our user id");
        }
        if (planCode == null && priceId != null) {
            Long versionId = billing.findVersionIdByStripePrice(priceId);
            if (versionId != null) {
                PlanVersionRow owner = billing.findVersion(versionId);
                if (owner != null) {
                    PlanRow plan = billing.findPlanById(owner.getPlanId());
                    if (plan != null) {
                        planCode = plan.getCode();
                    }
                }
            }
        }
        String status = PCommerceRequests.text(remote, "status");
        String localStatus = status != null && Set.of("active", "trialing", "past_due").contains(status)
                ? "active" : "canceled";
        LocalDateTime[] bounds = periodBounds(remote, planCode);
        SubscriptionRow row = upsertSubscription(userId, planCode, remoteId,
                PCommerceRequests.text(remote, "customer"), priceId, localStatus,
                bounds[0], bounds[1], sourceDramaId);
        if (row != null) {
            if ("canceled".equals(localStatus)) {
                if (row.getCanceledAt() == null) {
                    row.setCanceledAt(Times.now());
                }
                LocalDateTime passEnd = row.getPassEnd();
                if (passEnd != null && passEnd.isAfter(Times.now())) {
                    row.setStatus("active");
                }
            } else if (Boolean.TRUE.equals(PCommerceRequests.booleanValue(remote, "cancel_at_period_end"))) {
                if (row.getCanceledAt() == null) {
                    row.setCanceledAt(Times.now());
                }
            } else {
                row.setCanceledAt(null);
            }
            billing.updateSubscription(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("status", localStatus);
        return out;
    }

    /** billing._upsert_subscription: one active row per user, late events never resurrect. */
    private SubscriptionRow upsertSubscription(long userId, String planCode, String stripeSubscriptionId,
                                              String stripeCustomerId, String stripePriceId, String status,
                                              LocalDateTime periodStart, LocalDateTime periodEnd,
                                              Long sourceDramaId) {
        PlanRow plan = planCode == null || planCode.isEmpty() ? null : billing.findPlanByCode(planCode);
        if (plan == null) {
            return null;
        }
        PlanVersionRow version = latestVersion(plan);
        SubscriptionRow current = null;
        if (stripeSubscriptionId != null && !stripeSubscriptionId.isEmpty()) {
            current = billing.findSubscriptionByStripeId(stripeSubscriptionId);
        }
        if (current == null) {
            current = billing.findActiveSubscription(userId);
        }
        boolean created = false;
        if (current == null) {
            current = new SubscriptionRow();
            current.setUserId(userId);
            current.setPlanId(plan.getId());
            current.setPlanVersionId(version.getId());
            current.setBillingMode("subscription");
            created = true;
        } else if ("canceled".equals(current.getStatus())
                && current.getCanceledAt() != null
                && "active".equals(status)
                && !periodStart.isAfter(current.getCanceledAt())) {
            // A late or retried event generated before the cancellation must not
            // resurrect a membership an operator took back.
            return current;
        } else if (!plan.getId().equals(current.getPlanId())) {
            current.setPlanId(plan.getId());
            current.setPlanVersionId(version.getId());
        }
        current.setStatus(status);
        current.setCurrentPeriodStart(periodStart);
        current.setCurrentPeriodEnd(periodEnd);
        if (stripeSubscriptionId != null && !stripeSubscriptionId.isEmpty()) {
            current.setStripeSubscriptionId(stripeSubscriptionId);
        }
        if (stripeCustomerId != null && !stripeCustomerId.isEmpty()) {
            current.setStripeCustomerId(stripeCustomerId);
        }
        if (stripePriceId != null && !stripePriceId.isEmpty()) {
            current.setStripePriceId(stripePriceId);
        }
        if ("active".equals(status)) {
            current.setCanceledAt(null);
        }
        if (sourceDramaId != null && current.getSourceDramaId() == null) {
            current.setSourceDramaId(sourceDramaId);
        }
        if (created) {
            billing.insertSubscription(current);
        } else {
            billing.updateSubscription(current);
        }
        return current;
    }

    /** billing._period_bounds: top-level or per-item period, fallback to plan length. */
    private LocalDateTime[] periodBounds(JsonNode remote, String planCode) {
        JsonNode item = firstItem(remote);
        Long startEpoch = firstTruthyNumber(
                PCommerceRequests.longValue(remote, "current_period_start"),
                item == null ? null : PCommerceRequests.longValue(item, "current_period_start"));
        Long endEpoch = firstTruthyNumber(
                PCommerceRequests.longValue(remote, "current_period_end"),
                item == null ? null : PCommerceRequests.longValue(item, "current_period_end"));
        LocalDateTime start = asDatetime(startEpoch, null);
        LocalDateTime end = asDatetime(endEpoch, start.plusDays(planDays(planCode)));
        if (!end.isAfter(start)) {
            end = start.plusDays(planDays(planCode));
        }
        return new LocalDateTime[]{start, end};
    }

    private static JsonNode firstItem(JsonNode remote) {
        JsonNode items = PCommerceRequests.field(remote, "items");
        JsonNode data = items == null ? null : PCommerceRequests.field(items, "data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode first = data.get(0);
        return first == null || first.isNull() ? null : first;
    }

    private static Long firstTruthyNumber(Long primary, Long secondary) {
        if (primary != null && primary != 0L) {
            return primary;
        }
        return secondary;
    }

    private static LocalDateTime asDatetime(Long epoch, LocalDateTime fallback) {
        if (epoch != null && epoch != 0L) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
        }
        return fallback == null ? Times.now() : fallback;
    }

    private static int planDays(String planCode) {
        return PLAN_DAYS.getOrDefault(planCode == null ? "" : planCode, 30);
    }

    private static Long sourceDramaId(JsonNode metadata) {
        if (metadata == null) {
            return null;
        }
        JsonNode raw = PCommerceRequests.field(metadata, "source_drama_id");
        if (raw == null) {
            return null;
        }
        String text = raw.asText();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** billing.plan_code_for_price */
    private String planCodeForPrice(String priceId) {
        if (priceId == null || priceId.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : PRICE_ENV.entrySet()) {
            String configured = env.trimmed(entry.getValue(), "");
            if (configured.equals(priceId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** billing._latest_version */
    private PlanVersionRow latestVersion(PlanRow plan) {
        PlanVersionRow version = billing.latestVersion(plan.getId());
        if (version == null) {
            throw new ApiException(500, "plan has no price version");
        }
        return version;
    }

    /** billing.ensure_chargeable */
    private void ensureChargeable(String mode, Integer priceCents, String currency) {
        if (!"one_time".equals(mode)) {
            return;
        }
        String code = currency == null ? "USD" : currency;
        if (!"USD".equals(code.toUpperCase(Locale.ROOT))) {
            return;
        }
        int price = priceCents == null ? 0 : priceCents;
        if (price < MIN_ONE_TIME_CENTS) {
            throw new ApiException(400, String.format(Locale.ROOT,
                    "one-time price must be at least $%.2f (Stripe minimum)",
                    MIN_ONE_TIME_CENTS / 100.0));
        }
    }

    /** billing.ensure_stripe_price: the price of the right type for this version. */
    private String ensureStripePrice(PlanRow plan, PlanVersionRow version, String mode) {
        if (!BILLING_MODES.contains(mode)) {
            throw new ApiException(400, "unknown billing mode");
        }
        if ("one_time".equals(mode)) {
            if (version.getStripeOneTimePriceId() != null && !version.getStripeOneTimePriceId().isEmpty()) {
                return version.getStripeOneTimePriceId();
            }
        } else if (version.getStripePriceId() != null && !version.getStripePriceId().isEmpty()) {
            return version.getStripePriceId();
        }
        String envPrice = env.trimmed(PRICE_ENV.getOrDefault(plan.getCode(), ""), "");
        if ("subscription".equals(mode) && version.getVersion() != null
                && version.getVersion() == 1 && !envPrice.isEmpty()) {
            billing.setVersionStripePrice(version.getId(), envPrice);
            version.setStripePriceId(envPrice);
            return envPrice;
        }
        String product = env.trimmed(PRODUCT_ENV.getOrDefault(plan.getCode(), ""), "");
        if (product.isEmpty()) {
            throw new ApiException(503, "stripe product for " + plan.getCode() + " is not configured");
        }
        Map<String, String> payload = PCommerceStripeClient.form();
        payload.put("product", product);
        payload.put("unit_amount", String.valueOf(version.getPriceCents()));
        String currency = version.getCurrency() == null ? "USD" : version.getCurrency();
        payload.put("currency", currency.toLowerCase(Locale.ROOT));
        String nickname = plan.getNameEn() + " v" + version.getVersion() + " "
                + String.format(Locale.ROOT, "$%.2f", (version.getPriceCents() == null ? 0 : version.getPriceCents()) / 100.0)
                + ("one_time".equals(mode) ? " one-time" : "");
        payload.put("nickname", nickname);
        if ("subscription".equals(mode)) {
            payload.put("recurring[interval]", INTERVAL_OF.getOrDefault(plan.getCode(), "month"));
        }
        JsonNode price = stripe.request("/prices", payload, "POST");
        String priceId = PCommerceRequests.text(price, "id");
        if (priceId == null) {
            throw new IllegalStateException("stripe price response is missing id");
        }
        if ("one_time".equals(mode)) {
            billing.setVersionStripeOneTimePrice(version.getId(), priceId);
            version.setStripeOneTimePriceId(priceId);
        } else {
            billing.setVersionStripePrice(version.getId(), priceId);
            version.setStripePriceId(priceId);
        }
        return priceId;
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = PCommerceRequests.field(node, name);
        if (value == null) {
            throw new IllegalStateException("payload is missing " + name);
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String name) {
        Long value = PCommerceRequests.longValue(node, name);
        if (value == null) {
            throw new IllegalStateException("payload is missing " + name);
        }
        return value;
    }
}
