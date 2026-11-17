package us.minitv.admin.core.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreDramaMapper;
import us.minitv.admin.core.mapper.AdminCoreEpisodeMapper;
import us.minitv.admin.core.mapper.AdminCoreReviewMapper;
import us.minitv.admin.core.model.AdminCoreReviewRow;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.ops.row.AdminUserRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Supplier review queue (ops/review_router.py). */
@Service
public class AdminCoreReviewService {

    private static final Set<String> REVIEW_STATES = Set.of("pending", "approved", "rejected");
    private static final Map<String, Set<String>> DRAMA_TRANSITIONS = Map.of(
            "draft", Set.of("in_review"),
            "in_review", Set.of("draft", "published"),
            "published", Set.of("unpublished"),
            "unpublished", Set.of("in_review", "published"));
    private static final Map<String, Set<String>> EPISODE_TRANSITIONS = Map.of(
            "not_uploaded", Set.of("transcoding"),
            "transcoding", Set.of("in_review", "transcode_failed"),
            "transcode_failed", Set.of("transcoding"),
            "in_review", Set.of("published", "not_uploaded"),
            "published", Set.of("unpublished", "not_uploaded"),
            "unpublished", Set.of("published", "not_uploaded"));

    private final AdminCoreReviewMapper reviews;
    private final AdminCoreDramaMapper dramas;
    private final AdminCoreEpisodeMapper episodes;
    private final AdminCoreAuditService audit;

    public AdminCoreReviewService(AdminCoreReviewMapper reviews,
                                  AdminCoreDramaMapper dramas,
                                  AdminCoreEpisodeMapper episodes,
                                  AdminCoreAuditService audit) {
        this.reviews = reviews;
        this.dramas = dramas;
        this.episodes = episodes;
        this.audit = audit;
    }

    public Map<String, Object> listReviews(String state, String q, int page, int pageSize) {
        AdminCoreSupport.paging(page, pageSize);
        String stateFilter = state == null ? "pending" : state;
        if (!REVIEW_STATES.contains(stateFilter)) {
            throw ApiException.unprocessable("state: String should match pattern '^(pending|approved|rejected)$'");
        }
        String qFilter = q == null || q.isEmpty() ? null : q.trim();
        boolean onlyUnpublished = "approved".equals(stateFilter);

        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : reviews.reviewStateCounts()) {
            Object key = row.get("review_state");
            Object count = row.get("n");
            if (key instanceof String name && count instanceof Number number) {
                counts.put(name, number.longValue());
            }
        }
        long pending = counts.getOrDefault("pending", 0L);
        long rejected = counts.getOrDefault("rejected", 0L);
        long pendingPublish = reviews.countPendingPublish();
        long total = reviews.countReviews(stateFilter, onlyUnpublished, qFilter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminCoreReviewRow row : reviews.listReviews(stateFilter, onlyUnpublished, qFilter,
                pageSize, (page - 1) * pageSize)) {
            items.add(rowOut(row));
        }
        Map<String, Object> countOut = new LinkedHashMap<>();
        countOut.put("pending", pending);
        countOut.put("approved", pendingPublish);
        countOut.put("rejected", rejected);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("counts", countOut);
        return out;
    }

    @Transactional
    public Map<String, Object> batchDecision(AdminUserRow actor, List<Long> dramaIds, String decision, String note) {
        requireDecision(decision);
        if (dramaIds == null || dramaIds.isEmpty()) {
            throw ApiException.unprocessable("drama_ids: List should have at least 1 item");
        }
        if (dramaIds.size() > 50) {
            throw ApiException.unprocessable("drama_ids: List should have at most 50 items");
        }
        String rawNote = note == null ? "" : note;
        if (rawNote.length() > 500) {
            throw ApiException.unprocessable("note: String should have at most 500 characters");
        }
        String cleanNote = rawNote.trim();
        if ("reject".equals(decision) && cleanNote.isEmpty()) {
            throw ApiException.unprocessable("驳回必须填写原因");
        }
        String batchId = UUID.randomUUID().toString().replace("-", "");
        List<Long> done = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long dramaId : new LinkedHashSet<>(dramaIds)) {
            String reason = decideOne(actor, dramaId, decision, cleanNote, batchId);
            if (reason == null) {
                done.add(dramaId);
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", dramaId);
                item.put("reason", reason);
                skipped.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("done", done.size());
        out.put("done_ids", done);
        out.put("skipped", skipped);
        out.put("batch_id", batchId);
        return out;
    }

    @Transactional
    public Map<String, Object> oneDecision(AdminUserRow actor, long dramaId, String decision, String note) {
        requireDecision(decision);
        String rawNote = note == null ? "" : note;
        if (rawNote.length() > 500) {
            throw ApiException.unprocessable("note: String should have at most 500 characters");
        }
        String cleanNote = rawNote.trim();
        if ("reject".equals(decision) && cleanNote.isEmpty()) {
            throw ApiException.unprocessable("驳回必须填写原因");
        }
        String reason = decideOne(actor, dramaId, decision, cleanNote, UUID.randomUUID().toString().replace("-", ""));
        if (reason != null) {
            throw ApiException.conflict(reason);
        }
        DramaRow drama = dramas.findById(dramaId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", drama.getId());
        out.put("review_state", drama.getReviewState());
        out.put("review_note", drama.getReviewNote());
        return out;
    }

    @Transactional
    public Map<String, Object> publish(AdminUserRow actor, long dramaId) {
        DramaRow drama = dramas.findById(dramaId);
        if (drama == null) {
            throw ApiException.notFound("drama not found");
        }
        if (drama.getSupplierId() == null) {
            throw ApiException.badRequest("该接口只用于上剧方剧集");
        }
        if (!"approved".equals(drama.getReviewState())) {
            throw new ApiException(409, ApiException.detail("not_approved", "审核通过后才能上架"));
        }
        List<EpisodeRow> dramaEpisodes = episodes.listByDrama(drama.getId());
        if (dramaEpisodes.isEmpty()) {
            throw ApiException.conflict("没有分集，无法上架");
        }
        List<Integer> notReady = new ArrayList<>();
        for (EpisodeRow episode : dramaEpisodes) {
            if (!"in_review".equals(episode.getStatus()) && !"published".equals(episode.getStatus())) {
                notReady.add(episode.getEpisodeNumber());
            }
        }
        if (!notReady.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", "episodes_not_ready");
            detail.put("message", "第 " + notReady + " 集未就绪");
            throw new ApiException(409, detail);
        }
        LocalDateTime now = Times.now();
        try {
            for (EpisodeRow episode : dramaEpisodes) {
                if ("in_review".equals(episode.getStatus())) {
                    requireTransition(EPISODE_TRANSITIONS, "episode", episode.getStatus(), "published");
                    episodes.updateStatus(episode.getId(), "published", now);
                }
            }
            String status = drama.getStatus();
            if ("draft".equals(status)) {
                requireTransition(DRAMA_TRANSITIONS, "drama", status, "in_review");
                dramas.updateStatus(drama.getId(), "in_review", now);
                status = "in_review";
            }
            requireTransition(DRAMA_TRANSITIONS, "drama", status, "published");
            dramas.updateStatusAndPublished(drama.getId(), "published", now, now);
        } catch (ApiException ex) {
            throw ex;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("episodes", dramaEpisodes.size());
        payload.put("supplier_id", drama.getSupplierId());
        audit.record(actor.getId(), "drama_partner_publish", "drama", drama.getId(), payload);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", drama.getId());
        out.put("status", "published");
        out.put("episodes", dramaEpisodes.size());
        return out;
    }

    // ---- internals ----

    /** Returns null on success, or a skip reason (ops/review_router.py _decide_one). */
    private String decideOne(AdminUserRow actor, long dramaId, String decision, String note, String batchId) {
        String reviewState = "approve".equals(decision) ? "approved" : "rejected";
        String reviewNote = "reject".equals(decision) ? note : null;
        LocalDateTime now = Times.now();
        int affected = reviews.applyDecision(dramaId, reviewState, reviewNote, now, actor.getId(), now);
        if (affected == 0) {
            DramaRow drama = dramas.findById(dramaId);
            if (drama == null) {
                return "剧集不存在";
            }
            return "当前状态 " + drama.getReviewState() + "，不是待审核";
        }
        DramaRow drama = dramas.findById(dramaId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", note);
        payload.put("batch_id", batchId);
        payload.put("supplier_id", drama.getSupplierId());
        audit.record(actor.getId(), "drama_review_" + decision, "drama", drama.getId(), payload);
        return null;
    }

    private static void requireDecision(String decision) {
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw ApiException.unprocessable("decision: String should match pattern '^(approve|reject)$'");
        }
    }

    private static void requireTransition(Map<String, Set<String>> matrix, String entity,
                                          String fromStatus, String toStatus) {
        Set<String> allowed = matrix.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw ApiException.conflict("illegal " + entity + " transition: " + fromStatus + " -> " + toStatus);
        }
    }

    private static Map<String, Object> rowOut(AdminCoreReviewRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("title", row.getTitle());
        out.put("cover_url", row.getCoverUrl());
        out.put("status", row.getStatus());
        out.put("review_state", row.getReviewState());
        out.put("review_note", row.getReviewNote());
        out.put("free_episode_count", row.getFreeEpisodeCount());
        out.put("episode_count", row.getEpisodeCount());
        out.put("submitted_at", row.getSubmittedAt());
        out.put("reviewed_at", row.getReviewedAt());
        if (row.getSupplierId() == null || row.getSupplierName() == null) {
            out.put("supplier", null);
        } else {
            Map<String, Object> supplier = new LinkedHashMap<>();
            supplier.put("id", row.getSupplierId());
            supplier.put("name", row.getSupplierName());
            supplier.put("contact_email", row.getSupplierContactEmail());
            out.put("supplier", supplier);
        }
        return out;
    }
}
