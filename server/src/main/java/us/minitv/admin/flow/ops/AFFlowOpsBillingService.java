package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.billing.row.SubscriptionRow;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.common.row.AuditLogRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan catalog, the global billing switch, Stripe price provisioning and the
 * admin-side grant/revoke of time-based membership.
 */
@Service
public class AFFlowOpsBillingService {

    static final String MODE_SUBSCRIPTION = "subscription";
    static final String MODE_ONE_TIME = "one_time";
    static final int MIN_ONE_TIME_CENTS = 50;
    static final int MAX_GRANT_DAYS = 3650;

    private final AFFlowOpsBillingMapper billing;
    private final AFFlowOpsAuditMapper audit;
    private final AFFlowOpsStripeClient stripe;

    public AFFlowOpsBillingService(AFFlowOpsBillingMapper billing, AFFlowOpsAuditMapper audit,
                                   AFFlowOpsStripeClient stripe) {
        this.billing = billing;
        this.audit = audit;
        this.stripe = stripe;
    }

    // ---- plan catalog / billing switch ------------------------------------

    public Map<String, Object> adminPlans() {
        return AFFlowOpsSupport.map("items", listCurrentPlans(), "billing_mode", billingMode());
    }

    public List<Map<String, Object>> listCurrentPlans() {
        String mode = billingMode();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanRow plan : billing.listActivePlans()) {
            PlanVersionRow version = billing.findLatestVersion(plan.getId());
            if (version == null) {
                continue;
            }
            out.add(AFFlowOpsSupport.map(
                    "code", plan.getCode(),
                    "name_en", plan.getNameEn(),
                    "name_zh", plan.getNameZh(),
                    "price_cents", version.getPriceCents(),
                    "currency", version.getCurrency(),
                    "version", version.getVersion(),
                    "version_id", version.getId(),
                    "benefits", AFFlowOpsSupport.readMap(version.getBenefits()),
                    "billing_mode", mode));
        }
        return out;
    }

    @Transactional
    public BillingSettingRow ensureSettings() {
        BillingSettingRow row = billing.findBillingSettings();
        if (row == null) {
            billing.insertBillingSettings(MODE_ONE_TIME);
            row = billing.findBillingSettings();
        }
        return row;
    }

    public String billingMode() {
        return ensureSettings().getBillingMode();
    }

    @Transactional
    public Map<String, Object> readBillingSettings() {
        BillingSettingRow row = ensureSettings();
        return AFFlowOpsSupport.map(
                "billing_mode", row.getBillingMode(),
                "updated_at", row.getUpdatedAt(),
                "updated_by", row.getUpdatedBy());
    }

    @Transactional
    public Map<String, Object> updateBillingSettings(String mode, long actorId) {
        if (mode == null) {
            throw ApiException.unprocessable("Field required");
        }
        BillingSettingRow row = setBillingMode(mode, actorId);
        List<Map<String, Object>> provisioned = provisionPricesForMode(row.getBillingMode());
        AuditLogRow log = auditRow(actorId, "billing_mode_change", "billing_settings", row.getId().longValue(),
                AFFlowOpsSupport.map("billing_mode", row.getBillingMode(), "prices", provisioned));
        audit.insert(log);
        return AFFlowOpsSupport.map(
                "billing_mode", row.getBillingMode(),
                "prices", provisioned,
                "updated_at", row.getUpdatedAt());
    }

    private BillingSettingRow setBillingMode(String mode, long actorId) {
        if (!MODE_SUBSCRIPTION.equals(mode) && !MODE_ONE_TIME.equals(mode)) {
            throw new ApiException(400, "unknown billing mode");
        }
        ensureSettings();
        billing.updateBillingSettings(mode, actorId);
        return billing.findBillingSettings();
    }

    public List<Map<String, Object>> provisionPricesForMode(String mode) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanRow plan : billing.listActivePlans()) {
            PlanVersionRow version = billing.findLatestVersion(plan.getId());
            if (version == null) {
                out.add(AFFlowOpsSupport.map("code", plan.getCode(), "ok", false,
                        "error", "plan has no price version"));
                continue;
            }
            try {
                String priceId = ensureStripePrice(plan, version, mode);
                out.add(AFFlowOpsSupport.map("code", plan.getCode(), "price_id", priceId, "ok", true));
            } catch (ApiException ex) {
                out.add(AFFlowOpsSupport.map("code", plan.getCode(), "ok", false,
                        "error", String.valueOf(ex.detail())));
            }
        }
        return out;
    }

    @Transactional
    public Map<String, Object> changePrice(String code, AFFlowOpsBodies.PriceIn body, long actorId) {
        if (body == null || body.price_cents() == null) {
            throw ApiException.unprocessable("Field required");
        }
        int priceCents = body.price_cents();
        String mode = billingMode();
        ensureChargeable(mode, priceCents, "USD");
        PlanRow plan = billing.findPlanByCode(code);
        if (plan == null) {
            throw ApiException.notFound("plan not found");
        }
        PlanVersionRow latest = billing.findLatestVersion(plan.getId());
        PlanVersionRow version = new PlanVersionRow();
        version.setPlanId(plan.getId());
        version.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        version.setPriceCents(priceCents);
        version.setCurrency("USD");
        version.setBenefits(Json.write(body.benefits() == null ? Map.of() : body.benefits()));
        version.setNote("");
        billing.insertPlanVersion(version);
        AuditLogRow log = auditRow(actorId, "plan_price_change", "plan", plan.getId(),
                AFFlowOpsSupport.map("version", version.getVersion(), "price_cents", priceCents));
        audit.insert(log);
        // Best effort: a bad Stripe key/amount must not undo the local price change.
        try {
            ensureStripePrice(plan, version, billingMode());
        } catch (ApiException ignored) {
            // retried on checkout in the Python service; same here.
        }
        return AFFlowOpsSupport.map(
                "version", version.getVersion(),
                "price_cents", version.getPriceCents(),
                "stripe_price_id", version.getStripePriceId());
    }

    private void ensureChargeable(String mode, int priceCents, String currency) {
        if (!MODE_ONE_TIME.equals(mode)) {
            return;
        }
        if (currency != null && !"USD".equalsIgnoreCase(currency)) {
            return;
        }
        if (priceCents < MIN_ONE_TIME_CENTS) {
            throw new ApiException(400, "one-time price must be at least $0.50 (Stripe minimum)");
        }
    }

    String ensureStripePrice(PlanRow plan, PlanVersionRow version, String mode) {
        if (!MODE_SUBSCRIPTION.equals(mode) && !MODE_ONE_TIME.equals(mode)) {
            throw new ApiException(400, "unknown billing mode");
        }
        if (MODE_ONE_TIME.equals(mode)) {
            if (notBlank(version.getStripeOneTimePriceId())) {
                return version.getStripeOneTimePriceId();
            }
        } else if (notBlank(version.getStripePriceId())) {
            return version.getStripePriceId();
        }
        String envPrice = stripe.env(AFFlowOpsStripeClient.PRICE_ENV.getOrDefault(plan.getCode(), ""));
        if (MODE_SUBSCRIPTION.equals(mode) && Integer.valueOf(1).equals(version.getVersion())
                && !envPrice.isEmpty()) {
            billing.updateStripePriceId(version.getId(), envPrice);
            version.setStripePriceId(envPrice);
            return envPrice;
        }
        String product = stripe.env(AFFlowOpsStripeClient.PRODUCT_ENV.getOrDefault(plan.getCode(), ""));
        if (product.isEmpty()) {
            throw new ApiException(503, "stripe product for " + plan.getCode() + " is not configured");
        }
        String currency = notBlank(version.getCurrency()) ? version.getCurrency() : "USD";
        String nickname = plan.getNameEn() + " v" + version.getVersion() + " $"
                + String.format(java.util.Locale.ROOT, "%.2f", version.getPriceCents() / 100.0)
                + (MODE_ONE_TIME.equals(mode) ? " one-time" : "");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("product", product);
        payload.put("unit_amount", String.valueOf(version.getPriceCents()));
        payload.put("currency", currency.toLowerCase());
        payload.put("nickname", nickname);
        if (MODE_SUBSCRIPTION.equals(mode)) {
            payload.put("recurring[interval]",
                    AFFlowOpsStripeClient.INTERVAL_OF.getOrDefault(plan.getCode(), "month"));
        }
        Map<String, Object> price = stripe.request("/prices", payload, "POST");
        Object idValue = price.get("id");
        String priceId = idValue == null ? null : String.valueOf(idValue);
        if (MODE_ONE_TIME.equals(mode)) {
            billing.updateStripeOneTimePriceId(version.getId(), priceId);
            version.setStripeOneTimePriceId(priceId);
        } else {
            billing.updateStripePriceId(version.getId(), priceId);
            version.setStripePriceId(priceId);
        }
        return priceId;
    }

    // ---- membership --------------------------------------------------------

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

    public Map<String, Object> activeMembership(long userId) {
        SubscriptionRow subscription = billing.findActiveSubscription(userId);
        PlanRow plan = subscription == null ? null : billing.findPlanById(subscription.getPlanId());
        Map<String, Object> inactive = AFFlowOpsSupport.map(
                "active", false,
                "plan_code", null,
                "until", null,
                "canceling", false,
                "billing_mode", null,
                "subscription_until", null,
                "pass_until", null);
        if (subscription == null || plan == null) {
            return inactive;
        }
        LocalDateTime now = Times.now();
        LocalDateTime subEnd = subscription.getCurrentPeriodEnd();
        LocalDateTime passEnd = subscription.getPassEnd();
        List<LocalDateTime> live = new ArrayList<>();
        if (subEnd != null && subEnd.isAfter(now)) {
            live.add(subEnd);
        }
        if (passEnd != null && passEnd.isAfter(now)) {
            live.add(passEnd);
        }
        if (live.isEmpty()) {
            inactive.put("subscription_until", subEnd);
            inactive.put("pass_until", passEnd);
            return inactive;
        }
        LocalDateTime until = live.stream().max(LocalDateTime::compareTo).orElseThrow();
        boolean oneTime = passEnd != null && passEnd.equals(until)
                && (subEnd == null || passEnd.isAfter(subEnd));
        String mode = oneTime ? MODE_ONE_TIME : MODE_SUBSCRIPTION;
        boolean canceling = MODE_SUBSCRIPTION.equals(mode)
                && subscription.getCanceledAt() != null
                && subEnd != null && subEnd.isAfter(now);
        return AFFlowOpsSupport.map(
                "active", true,
                "plan_code", plan.getCode(),
                "until", until,
                "canceling", canceling,
                "billing_mode", mode,
                "subscription_until", subEnd,
                "pass_until", passEnd);
    }

    public Map<String, Object> membershipDetail(long userId) {
        Map<String, Object> membership = activeMembership(userId);
        SubscriptionRow row = billing.findActiveSubscription(userId);
        PlanRow plan = row == null ? null : billing.findPlanById(row.getPlanId());
        membership.put("source", row == null ? null
                : (notBlank(row.getStripeSubscriptionId()) ? MODE_SUBSCRIPTION : MODE_ONE_TIME));
        membership.put("plan_name", plan == null ? null : planName(plan));
        if (membership.get("plan_code") == null && plan != null) {
            membership.put("plan_code", plan.getCode());
        }
        membership.put("has_stripe_subscription", row != null && notBlank(row.getStripeSubscriptionId()));

        List<Map<String, Object>> history = new ArrayList<>();
        for (SubscriptionRow sub : billing.listSubscriptions(userId, 6)) {
            PlanRow subPlan = billing.findPlanById(sub.getPlanId());
            if (subPlan == null) {
                continue;
            }
            history.add(describe(sub, subPlan));
        }
        membership.put("history", history);

        AuditLogRow last = audit.findLastMembershipAudit(userId);
        membership.put("last_adjust", last == null ? null : AFFlowOpsSupport.map(
                "action", last.getAction(),
                "actor_id", last.getActorId(),
                "at", last.getCreatedAt(),
                "payload", AFFlowOpsSupport.readMap(last.getPayload())));
        return membership;
    }

    private static Map<String, Object> describe(SubscriptionRow sub, PlanRow plan) {
        return AFFlowOpsSupport.map(
                "id", sub.getId(),
                "plan_code", plan.getCode(),
                "plan_name", planName(plan),
                "status", sub.getStatus(),
                "start", sub.getCurrentPeriodStart(),
                "end", sub.getCurrentPeriodEnd(),
                "pass_end", sub.getPassEnd(),
                "canceled_at", sub.getCanceledAt(),
                "billing_mode", sub.getBillingMode(),
                "source", notBlank(sub.getStripeSubscriptionId()) ? MODE_SUBSCRIPTION : MODE_ONE_TIME);
    }

    @Transactional
    public Map<String, Object> grantMembership(long userId, AFFlowOpsBodies.MembershipGrantIn body, long actorId) {
        if (body == null || body.plan_code() == null) {
            throw ApiException.unprocessable("Field required");
        }
        String mode = body.mode() == null ? "extend" : body.mode();
        if (!"extend".equals(mode) && !"restart".equals(mode)) {
            throw ApiException.unprocessable("unknown grant mode");
        }
        String planCode = body.plan_code();
        PlanRow plan = billing.findActivePlanByCode(planCode);
        if (plan == null) {
            throw ApiException.notFound("plan not found");
        }
        PlanVersionRow version = billing.findLatestVersion(plan.getId());
        if (version == null) {
            throw new ApiException(500, "plan has no price version");
        }
        int length = body.days() == null
                ? AFFlowOpsStripeClient.PLAN_DAYS.getOrDefault(planCode, 30)
                : body.days();
        if (length < 1 || length > MAX_GRANT_DAYS) {
            throw ApiException.unprocessable("天数需在 1–" + MAX_GRANT_DAYS + " 之间");
        }
        String reason = AFFlowOpsSupport.trim(body.reason());
        if (reason.isEmpty()) {
            throw ApiException.unprocessable("请填写调整原因");
        }
        LocalDateTime beforeUntil = accessUntil(userId);
        SubscriptionRow row = billing.lockActiveSubscription(userId);
        if ("restart".equals(mode) && row != null && notBlank(row.getStripeSubscriptionId())) {
            throw new ApiException(409, "该用户有自动续费订阅，请改用「顺延」");
        }
        LocalDateTime now = Times.now();
        if (row == null) {
            row = new SubscriptionRow();
            row.setUserId(userId);
            row.setPlanId(plan.getId());
            row.setPlanVersionId(version.getId());
            row.setStatus("active");
            row.setCurrentPeriodStart(now);
            row.setCurrentPeriodEnd(now);
            row.setBillingMode(MODE_ONE_TIME);
            billing.insertSubscription(row);
        }
        LocalDateTime base;
        if ("extend".equals(mode)) {
            base = now;
            for (LocalDateTime candidate : new LocalDateTime[]{row.getPassEnd(), row.getCurrentPeriodEnd()}) {
                if (candidate != null && candidate.isAfter(base)) {
                    base = candidate;
                }
            }
        } else {
            base = now;
        }
        row.setPassEnd(base.plusDays(length));
        if (!notBlank(row.getStripeSubscriptionId())) {
            row.setPlanId(plan.getId());
            row.setPlanVersionId(version.getId());
        }
        billing.updateSubscriptionGrant(row);
        AuditLogRow log = auditRow(actorId, "membership_grant", "user", userId,
                AFFlowOpsSupport.map(
                        "plan_code", planCode,
                        "days", length,
                        "mode", mode,
                        "reason", reason,
                        "before_until", beforeUntil,
                        "after_until", row.getPassEnd()));
        audit.insert(log);
        return AFFlowOpsSupport.map(
                "before_until", beforeUntil,
                "after_until", row.getPassEnd(),
                "added_days", length,
                "plan_code", planCode,
                "mode", mode);
    }

    @Transactional
    public Map<String, Object> revokeMembership(long userId, AFFlowOpsBodies.MembershipRevokeIn body, long actorId) {
        String mode = body == null || body.mode() == null ? "now" : body.mode();
        if (!"now".equals(mode) && !"period_end".equals(mode)) {
            throw ApiException.unprocessable("unknown revoke mode");
        }
        String reason = AFFlowOpsSupport.trim(body.reason());
        if (reason.isEmpty()) {
            throw ApiException.unprocessable("请填写取消原因");
        }
        SubscriptionRow row = billing.lockActiveSubscription(userId);
        LocalDateTime now = Times.now();
        LocalDateTime beforeUntil = accessUntil(userId);
        if (row == null || beforeUntil == null) {
            throw new ApiException(409, "该用户当前没有有效会员");
        }
        String stripeId = row.getStripeSubscriptionId();
        if ("period_end".equals(mode)) {
            if (!notBlank(stripeId)) {
                throw new ApiException(409, "时长包没有续费概念，请选择「立即收回」");
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("cancel_at_period_end", "true");
            stripe.request("/subscriptions/" + stripeId, data, "POST");
            row.setCanceledAt(now);
            billing.updateSubscriptionCanceledAt(row);
            AuditLogRow log = auditRow(actorId, "membership_revoke", "user", userId,
                    AFFlowOpsSupport.map(
                            "mode", mode,
                            "reason", reason,
                            "before_until", beforeUntil,
                            "after_until", beforeUntil,
                            "stripe_canceled", true));
            audit.insert(log);
            return AFFlowOpsSupport.map(
                    "mode", mode,
                    "before_until", beforeUntil,
                    "after_until", beforeUntil,
                    "stripe_canceled", true);
        }
        boolean stripeCanceled = notBlank(stripeId);
        if (stripeCanceled) {
            stripe.request("/subscriptions/" + stripeId, null, "DELETE");
        }
        row.setPassEnd(now);
        row.setCurrentPeriodEnd(now);
        row.setCanceledAt(now);
        row.setStatus("canceled");
        billing.updateSubscriptionRevoke(row);
        AuditLogRow log = auditRow(actorId, "membership_revoke", "user", userId,
                AFFlowOpsSupport.map(
                        "mode", mode,
                        "reason", reason,
                        "before_until", beforeUntil,
                        "after_until", now,
                        "stripe_canceled", stripeCanceled));
        audit.insert(log);
        return AFFlowOpsSupport.map(
                "mode", mode,
                "before_until", beforeUntil,
                "after_until", now,
                "stripe_canceled", stripeCanceled);
    }

    // ---- helpers -----------------------------------------------------------

    static String planName(PlanRow plan) {
        return notBlank(plan.getNameZh()) ? plan.getNameZh() : plan.getNameEn();
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private AuditLogRow auditRow(long actorId, String action, String targetType, long targetId,
                                 Map<String, Object> payload) {
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setPayload(Json.write(payload));
        return log;
    }
}
