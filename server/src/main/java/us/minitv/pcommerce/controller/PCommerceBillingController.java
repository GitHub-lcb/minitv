package us.minitv.pcommerce.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.pcommerce.service.PCommerceBillingService;
import us.minitv.pcommerce.support.PCommerceRequests;
import us.minitv.security.CurrentUser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plans, order history, Stripe Checkout and the two webhooks. */
@RestController
public class PCommerceBillingController {

    private final PCommerceBillingService billing;

    public PCommerceBillingController(PCommerceBillingService billing) {
        this.billing = billing;
    }

    public record CheckoutIn(@JsonProperty("plan_code") String planCode,
                             @JsonProperty("success_url") String successUrl,
                             @JsonProperty("cancel_url") String cancelUrl,
                             @JsonProperty("source_drama_id") Long sourceDramaId) {
    }

    /** GET /v1/plans */
    @GetMapping("/v1/plans")
    public Map<String, Object> plans() {
        List<Map<String, Object>> items = billing.listCurrentPlans();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("billing_mode", billing.billingMode());
        return out;
    }

    /** GET /v1/me/orders */
    @GetMapping("/v1/me/orders")
    public Map<String, Object> orders(@RequestParam(value = "limit", defaultValue = "20") int limit,
                                      @RequestParam(value = "offset", defaultValue = "0") int offset,
                                      @CurrentUser UserRow user) {
        int size = Math.max(1, Math.min(limit, 50));
        return billing.listUserOrders(user.getId(), size, Math.max(0, offset));
    }

    /** POST /v1/billing/webhook (legacy HMAC-signed fake webhook). */
    @PostMapping("/v1/billing/webhook")
    public Map<String, Object> webhook(HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        String signature = request.getHeader("X-Pay-Signature");
        // json.loads sits outside the BillingError handler upstream: invalid JSON
        // surfaces as a 500, exactly like FastAPI.
        JsonNode payload = PCommerceRequests.readJson(body);
        return billing.applyWebhook(body, signature == null ? "" : signature, payload);
    }

    /** POST /v1/billing/cancel */
    @PostMapping("/v1/billing/cancel")
    public Map<String, Object> cancel(@CurrentUser UserRow user) {
        billing.cancelSubscription(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    /** POST /v1/billing/checkout */
    @PostMapping("/v1/billing/checkout")
    public Map<String, Object> checkout(@RequestBody CheckoutIn body,
                                       @CurrentUser UserRow user) {
        String planCode = PCommerceRequests.required(body.planCode(), "plan_code");
        return billing.createCheckoutSession(user.getId(), user.getEmail(), planCode,
                body.successUrl(), body.cancelUrl(), body.sourceDramaId());
    }

    /** POST /v1/billing/stripe/webhook */
    @PostMapping("/v1/billing/stripe/webhook")
    public Map<String, Object> stripeWebhook(HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        String signature = request.getHeader("Stripe-Signature");
        JsonNode payload;
        try {
            payload = PCommerceRequests.readJson(body);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "invalid payload");
        }
        return billing.applyStripeEvent(body, signature == null ? "" : signature, payload);
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        return request.getInputStream().readAllBytes();
    }
}
