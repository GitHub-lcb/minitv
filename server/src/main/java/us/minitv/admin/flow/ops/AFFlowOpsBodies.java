package us.minitv.admin.flow.ops;

import java.util.List;
import java.util.Map;

/**
 * Request bodies for the admin flow/ops endpoints. Component names mirror the
 * Python Pydantic field names exactly so Jackson binds the same JSON.
 */
public final class AFFlowOpsBodies {

    private AFFlowOpsBodies() {
    }

    public record GroupIn(String name, Integer position) {
    }

    public record GroupAddIn(Long drama_id, Boolean confirm) {
    }

    public record PositionIn(Integer position) {
    }

    public record CopyIn(String key, String en, String zh) {
    }

    public record RewardIn(String code, List<Integer> amounts) {
    }

    public record PriceIn(Integer price_cents, Map<String, Object> benefits) {
    }

    public record AdjustIn(Integer delta, String reason) {
    }

    public record MembershipGrantIn(String plan_code, Integer days, String mode, String reason) {
    }

    public record MembershipRevokeIn(String mode, String reason) {
    }

    public record CommentModIn(String status) {
    }

    public record ModerationSettingsIn(
            Boolean enabled,
            String base_url,
            String model,
            List<String> categories,
            String system_prompt,
            Double approve_threshold,
            Double block_threshold,
            String fallback_action,
            Integer timeout_seconds,
            String api_key,
            Boolean clear_api_key) {
    }

    public record ModerationTestIn(String text, String api_key) {
    }

    public record BillingModeIn(String billing_mode) {
    }

    public record TabIn(
            String code,
            String title_en,
            String title_zh,
            Integer position,
            Boolean enabled,
            Long group_id) {
    }

    public record BannerIn(
            Long drama_id,
            String slot,
            String status,
            Integer position,
            String kicker,
            String headline) {
    }
}
