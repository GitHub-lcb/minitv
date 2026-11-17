package us.minitv.admin.flow.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.ops.row.ModerationSettingRow;
import us.minitv.social.row.CommentRow;
import us.minitv.account.row.UserRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Moderation policy CRUD, dry-run test and the comment moderation queue. */
@Service
public class AFFlowOpsModerationService {

    private static final Set<String> COMMENT_STATUSES = Set.of("approved", "deleted", "pending");

    private final AFFlowOpsModerationMapper moderation;
    private final AFFlowOpsContentMapper content;
    private final AFFlowOpsUserMapper users;
    private final AFFlowOpsAuditMapper audit;
    private final AFFlowOpsModerationGateway gateway;

    public AFFlowOpsModerationService(AFFlowOpsModerationMapper moderation, AFFlowOpsContentMapper content,
                                      AFFlowOpsUserMapper users, AFFlowOpsAuditMapper audit,
                                      AFFlowOpsModerationGateway gateway) {
        this.moderation = moderation;
        this.content = content;
        this.users = users;
        this.audit = audit;
        this.gateway = gateway;
    }

    // ---- settings ----------------------------------------------------------

    @Transactional
    public ModerationSettingRow ensureSettings() {
        ModerationSettingRow row = moderation.findSettings();
        if (row == null) {
            row = new ModerationSettingRow();
            row.setEnabled(false);
            row.setBaseUrl(gateway.defaultBaseUrl());
            row.setModel(AFFlowOpsModerationGateway.DEFAULT_MODEL);
            row.setCategories(Json.write(AFFlowOpsModerationGateway.DEFAULT_CATEGORIES));
            row.setSystemPrompt(AFFlowOpsModerationGateway.DEFAULT_SYSTEM_PROMPT);
            row.setApproveThreshold(0.75);
            row.setBlockThreshold(0.75);
            row.setFallbackAction("pending");
            row.setTimeoutSeconds(6);
            row.setApiKeySecret("");
            moderation.insertSettings(row);
            row = moderation.findSettings();
        }
        return row;
    }

    @Transactional
    public Map<String, Object> readSettings() {
        return payload(ensureSettings());
    }

    private Map<String, Object> payload(ModerationSettingRow row) {
        List<String> categories = row.getCategories() == null ? List.of()
                : Json.read(row.getCategories(), new TypeReference<List<String>>() {
                }, List.of());
        Map<String, Object> status = gateway.keyStatus(row);
        String secret = row.getApiKeySecret() == null ? "" : row.getApiKeySecret().trim();
        return AFFlowOpsSupport.map(
                "enabled", row.getEnabled(),
                "base_url", row.getBaseUrl(),
                "model", row.getModel(),
                "categories", categories,
                "system_prompt", row.getSystemPrompt(),
                "approve_threshold", row.getApproveThreshold(),
                "block_threshold", row.getBlockThreshold(),
                "fallback_action", row.getFallbackAction(),
                "timeout_seconds", row.getTimeoutSeconds(),
                "updated_at", row.getUpdatedAt(),
                "updated_by", row.getUpdatedBy(),
                "key", status,
                "key_from_database", !secret.isEmpty(),
                "key_env_configured", !gateway.envKey().isEmpty(),
                "default_base_url", gateway.defaultBaseUrl(),
                "default_categories", AFFlowOpsModerationGateway.DEFAULT_CATEGORIES,
                "default_prompt", AFFlowOpsModerationGateway.DEFAULT_SYSTEM_PROMPT);
    }

    @Transactional
    public Map<String, Object> updateSettings(AFFlowOpsBodies.ModerationSettingsIn body, long actorId) {
        if (body == null || body.enabled() == null || body.model() == null
                || body.approve_threshold() == null || body.block_threshold() == null
                || body.timeout_seconds() == null) {
            throw ApiException.unprocessable("Field required");
        }
        if (body.model().isEmpty() || body.model().length() > 64) {
            throw ApiException.unprocessable("String should have between 1 and 64 characters");
        }
        String rawBase = body.base_url() == null ? "https://api.openai.com/v1" : body.base_url();
        if (rawBase.length() > 200) {
            throw ApiException.unprocessable("String should have at most 200 characters");
        }
        String baseUrl = rawBase.strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw ApiException.unprocessable("base_url must start with http:// or https://");
        }
        List<String> categories = cleanCategories(body.categories());
        String systemPrompt = body.system_prompt() == null ? "" : body.system_prompt();
        if (systemPrompt.length() > 8000) {
            throw ApiException.unprocessable("String should have at most 8000 characters");
        }
        double approve = body.approve_threshold();
        double block = body.block_threshold();
        if (approve < 0 || approve > 1 || block < 0 || block > 1) {
            throw ApiException.unprocessable("Input should be between 0 and 1");
        }
        String fallback = body.fallback_action() == null ? "pending" : body.fallback_action();
        if (!AFFlowOpsModerationGateway.FALLBACK_ACTIONS.contains(fallback)) {
            throw ApiException.unprocessable("fallback_action must be pending or approved");
        }
        int timeout = body.timeout_seconds();
        if (timeout < 1 || timeout > 30) {
            throw ApiException.unprocessable("Input should be between 1 and 30");
        }
        String apiKey = body.api_key() == null || body.api_key().strip().isEmpty()
                ? null : body.api_key().strip();
        boolean clearKey = body.clear_api_key() != null && body.clear_api_key();

        ModerationSettingRow row = ensureSettings();
        Map<String, Object> before = AFFlowOpsSupport.map(
                "enabled", row.getEnabled(),
                "model", row.getModel(),
                "base_url", row.getBaseUrl(),
                "approve_threshold", row.getApproveThreshold(),
                "block_threshold", row.getBlockThreshold(),
                "fallback_action", row.getFallbackAction(),
                "timeout_seconds", row.getTimeoutSeconds(),
                "categories", row.getCategories() == null ? List.of()
                        : Json.read(row.getCategories(), new TypeReference<List<String>>() {
                        }, List.of()),
                "key_source", gateway.keyStatus(row).get("source"));

        String keyAction = "unchanged";
        if (clearKey) {
            gateway.clearApiKey(row);
            keyAction = "cleared";
        } else if (apiKey != null) {
            gateway.setApiKey(row, apiKey);
            keyAction = "set";
        }

        String model = body.model().strip();
        row.setEnabled(body.enabled());
        row.setBaseUrl(baseUrl);
        row.setModel(model);
        row.setCategories(Json.write(categories));
        row.setSystemPrompt(systemPrompt);
        row.setApproveThreshold(approve);
        row.setBlockThreshold(block);
        row.setFallbackAction(fallback);
        row.setTimeoutSeconds(timeout);
        row.setUpdatedBy(actorId);
        moderation.updateSettings(row);

        Map<String, Object> after = AFFlowOpsSupport.map(
                "enabled", body.enabled(),
                "model", model,
                "base_url", baseUrl,
                "approve_threshold", approve,
                "block_threshold", block,
                "fallback_action", fallback,
                "timeout_seconds", timeout,
                "categories", categories,
                "key_source", gateway.keyStatus(row).get("source"));
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("moderation_settings_change");
        log.setTargetType("moderation_settings");
        log.setTargetId(row.getId() == null ? 1L : row.getId().longValue());
        log.setPayload(Json.write(AFFlowOpsSupport.map(
                "before", before,
                "after", after,
                "key_action", keyAction,
                "key_masked", gateway.keyStatus(row).get("masked"),
                "prompt_chars", systemPrompt.length())));
        audit.insert(log);

        return payload(moderation.findSettings());
    }

    private static List<String> cleanCategories(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null) {
            return cleaned;
        }
        for (String item : values) {
            String text = (item == null ? "" : item).strip().toLowerCase();
            if (text.length() > 32) {
                text = text.substring(0, 32);
            }
            if (!text.isEmpty() && !cleaned.contains(text)) {
                cleaned.add(text);
            }
        }
        return cleaned.size() > 20 ? new ArrayList<>(cleaned.subList(0, 20)) : cleaned;
    }

    @Transactional
    public Map<String, Object> testModeration(AFFlowOpsBodies.ModerationTestIn body) {
        if (body == null || body.text() == null || body.text().isEmpty()) {
            throw ApiException.unprocessable("Field required");
        }
        if (body.text().length() > 1000) {
            throw ApiException.unprocessable("String should have at most 1000 characters");
        }
        String apiKey = body.api_key() == null || body.api_key().strip().isEmpty()
                ? null : body.api_key().strip();
        ModerationSettingRow settings = ensureSettings();
        AFFlowOpsModerationGateway.ModerationResult result;
        try {
            result = gateway.classify(body.text(), settings, apiKey);
        } catch (AFFlowOpsModerationGateway.ModerationException ex) {
            throw new ApiException(400, ex.getMessage());
        }
        return AFFlowOpsSupport.map(
                "enabled", settings.getEnabled(),
                "status", AFFlowOpsModerationGateway.decide(result.verdict(), result.score(), settings),
                "result", result.asDict());
    }

    // ---- comment queue -----------------------------------------------------

    public Map<String, Object> listComments(String status, Long dramaId, Long episodeId, String q,
                                            int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String statusFilter = status != null && !status.isEmpty() ? status : null;
        Long dramaFilter = dramaId != null && dramaId != 0 ? dramaId : null;
        Long episodeFilter = episodeId != null && episodeId != 0 ? episodeId : null;
        String needle = q == null ? "" : q.strip();
        String like = needle.isEmpty() ? null : "%" + needle + "%";

        long total = moderation.countComments(statusFilter, dramaFilter, episodeFilter, like);
        List<CommentRow> rows = moderation.searchComments(statusFilter, dramaFilter, episodeFilter, like,
                safePageSize, (safePage - 1) * safePageSize);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("approved", 0);
        counts.put("deleted", 0);
        counts.put("total", 0);
        long countTotal = 0;
        for (Map<String, Object> row : moderation.statusCounts(dramaFilter, episodeFilter, like)) {
            String rowStatus = String.valueOf(row.get("status"));
            long value = AFFlowOpsSupport.asLong(row.get("cnt"));
            counts.put(rowStatus, value);
            countTotal += value;
        }
        counts.put("total", countTotal);

        List<Map<String, Object>> items = new ArrayList<>();
        for (CommentRow row : rows) {
            DramaRow drama = content.findDrama(row.getDramaId());
            EpisodeRow episode = row.getEpisodeId() == null ? null : content.findEpisode(row.getEpisodeId());
            UserRow user = users.findUser(row.getUserId());
            items.add(AFFlowOpsSupport.map(
                    "id", row.getId(),
                    "body", row.getBody(),
                    "status", row.getStatus(),
                    "drama_id", row.getDramaId(),
                    "drama_title", drama == null ? null : drama.getTitle(),
                    "episode_id", row.getEpisodeId(),
                    "episode_number", episode == null ? null : episode.getEpisodeNumber(),
                    "user_id", row.getUserId(),
                    "user_display_name", user == null ? null : user.getDisplayName(),
                    "user_email", user == null ? null : user.getEmail(),
                    "created_at", row.getCreatedAt(),
                    "moderation_verdict", row.getModerationVerdict(),
                    "moderation_score", row.getModerationScore(),
                    "moderation_reason", row.getModerationReason(),
                    "moderation_model", row.getModerationModel(),
                    "moderation_state", row.getModerationState()));
        }
        return AFFlowOpsSupport.map(
                "items", items,
                "total", total,
                "counts", counts,
                "page", safePage,
                "page_size", safePageSize);
    }

    @Transactional
    public Map<String, Object> moderateComment(long commentId, AFFlowOpsBodies.CommentModIn body) {
        CommentRow row = moderation.findComment(commentId);
        if (row == null) {
            throw ApiException.notFound("comment not found");
        }
        String status = body == null ? null : body.status();
        if (status == null || !COMMENT_STATUSES.contains(status)) {
            throw ApiException.unprocessable("bad status");
        }
        row.setStatus(status);
        if ("queued".equals(row.getModerationState()) || "running".equals(row.getModerationState())) {
            row.setModerationState("canceled");
        }
        moderation.updateCommentModeration(row);
        return AFFlowOpsSupport.map("status", row.getStatus(), "moderation_state", row.getModerationState());
    }
}
