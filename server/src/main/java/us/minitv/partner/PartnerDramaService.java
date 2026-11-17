package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.partner.mapper.PartnerDramaMapper;
import us.minitv.partner.mapper.PartnerEpisodeMapper;
import us.minitv.partner.mapper.PartnerTagMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Supplier-facing drama management: taxonomy, listing, CRUD, cover upload,
 * submit-for-review and draft deletion. Ported 1:1 from
 * {@code partner_router} (P1/P2/P3).
 */
@Service
public class PartnerDramaService {

    private static final Set<String> UPDATE_FIELDS = Set.of(
            "title", "synopsis", "cover_url", "free_episode_count",
            "country_allowlist", "age_rating", "tag_ids");

    private final PartnerDramaMapper dramas;
    private final PartnerEpisodeMapper episodes;
    private final PartnerTagMapper tags;
    private final PartnerContentSupport support;
    private final PartnerAuditService audit;
    private final PartnerBunny bunny;

    public PartnerDramaService(PartnerDramaMapper dramas,
                               PartnerEpisodeMapper episodes,
                               PartnerTagMapper tags,
                               PartnerContentSupport support,
                               PartnerAuditService audit,
                               PartnerBunny bunny) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.tags = tags;
        this.support = support;
        this.audit = audit;
        this.bunny = bunny;
    }

    // ---- tags ----

    /** Only enabled tags; suppliers may pick from the platform taxonomy. */
    public Map<String, Object> listTags() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TagRow tag : tags.listEnabled()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tag.getId());
            item.put("name_zh", tag.getNameZh());
            item.put("name_en", tag.getNameEn());
            item.put("category", tag.getCategory());
            items.add(item);
        }
        return Map.of("items", items);
    }

    // ---- listing ----

    public Map<String, Object> list(AdminUserRow user, String status, String reviewState,
                                    String q, int page, int pageSize) {
        if (page < 1) {
            throw PartnerErrors.queryValidation("page",
                    "Input should be greater than or equal to 1", "greater_than_equal");
        }
        if (pageSize < 1) {
            throw PartnerErrors.queryValidation("page_size",
                    "Input should be greater than or equal to 1", "greater_than_equal");
        }
        if (pageSize > 100) {
            throw PartnerErrors.queryValidation("page_size",
                    "Input should be less than or equal to 100", "less_than_equal");
        }
        long supplierId = user.getSupplierId();
        String statusFilter = PartnerText.truthy(status) ? status : null;
        String reviewFilter = PartnerText.truthy(reviewState) ? reviewState : null;
        String query = q == null ? null : PartnerText.strip(q);
        if (!PartnerText.truthy(query)) {
            query = null;
        }

        long total = dramas.countOwned(supplierId, statusFilter, reviewFilter, query);
        List<DramaRow> rows = dramas.listOwned(supplierId, statusFilter, reviewFilter, query,
                pageSize, (page - 1) * pageSize);

        Map<String, Object> statusCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : dramas.statusCounts(supplierId)) {
            statusCounts.put(String.valueOf(row.get("status")), countValue(row.get("cnt")));
        }
        statusCounts.put("all", dramas.countOwned(supplierId, null, null, null));

        Map<String, Object> reviewCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : dramas.reviewCounts(supplierId)) {
            reviewCounts.put(String.valueOf(row.get("reviewState")), countValue(row.get("cnt")));
        }

        Map<String, Object> stageCounts = new LinkedHashMap<>();
        stageCounts.put("all", total);
        stageCounts.put("draft", dramas.countStage(supplierId,
                List.of("draft"), List.of("none", "rejected")));
        stageCounts.put("pending", dramas.countStage(supplierId, null, List.of("pending")));
        stageCounts.put("approved", dramas.countStage(supplierId,
                List.of("draft", "in_review"), List.of("approved")));
        stageCounts.put("published", dramas.countStage(supplierId, List.of("published"), null));
        stageCounts.put("unpublished", dramas.countStage(supplierId, List.of("unpublished"), null));

        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaRow row : rows) {
            items.add(support.dramaOut(row, false));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("total", total);
        payload.put("page", page);
        payload.put("page_size", pageSize);
        payload.put("status_counts", statusCounts);
        payload.put("review_counts", reviewCounts);
        payload.put("stage_counts", stageCounts);
        return payload;
    }

    // ---- create / read / update ----

    @Transactional
    public Map<String, Object> create(AdminUserRow user, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        String rawTitle = PartnerBody.requiredString(body, "title", 1, 200);
        String title = PartnerText.strip(rawTitle);
        if (!PartnerText.truthy(title)) {
            throw PartnerErrors.unprocessable("剧名不能为空");
        }
        String synopsis = PartnerText.strip(PartnerBody.stringOr(body, "synopsis", "", 5000));
        int freeEpisodes = PartnerBody.intOr(body, "free_episode_count",
                PartnerLimits.DEFAULT_FREE_EPISODES, 0, 1000);
        String countryAllowlist = PartnerText.strip(
                PartnerBody.stringOr(body, "country_allowlist", "", 200));
        Integer ageRating = PartnerBody.optionalInt(body, "age_rating", 0, 18);
        long supplierId = user.getSupplierId();

        DramaRow existing = dramas.findOwnedByTitle(supplierId, title);
        if (existing != null) {
            Map<String, Object> detail = PartnerErrors.detail("title_exists", "你已有同名剧");
            detail.put("drama_id", existing.getId());
            throw PartnerErrors.structured(409, detail);
        }

        DramaRow row = new DramaRow();
        row.setTitle(title);
        row.setSynopsis(synopsis);
        row.setSupplierId(supplierId);
        row.setFreeEpisodeCount(freeEpisodes);
        row.setCountryAllowlist(countryAllowlist);
        row.setAgeRating(ageRating);
        row.setCoinPricePerEpisode(0);
        row.setStatus("draft");
        row.setReviewState("none");
        row.setCoverUrl("");
        row.setPlayCount(0L);
        try {
            dramas.insertDrama(row);
        } catch (DuplicateKeyException ex) {
            throw PartnerErrors.conflict("同名剧已存在");
        }
        audit.partnerAction(user, "partner_drama_create", row.getId(), Map.of("title", title));
        return support.dramaOut(dramas.findById(row.getId()), true);
    }

    public Map<String, Object> get(AdminUserRow user, long dramaId) {
        DramaRow row = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        return support.dramaOut(row, true);
    }

    @Transactional
    public Map<String, Object> update(AdminUserRow user, long dramaId, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        DramaRow drama = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        support.requireEditable(drama);
        long supplierId = user.getSupplierId();

        Set<String> provided = new TreeSet<>();
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (UPDATE_FIELDS.contains(name)) {
                provided.add(name);
            }
        }

        boolean changed = false;
        boolean ageRatingSet = false;
        boolean reviewNoteSet = false;

        if (PartnerBody.present(body, "title") && !PartnerBody.isNull(body, "title")) {
            String title = PartnerText.strip(PartnerBody.optionalString(body, "title", 1, 200));
            if (!PartnerText.truthy(title)) {
                throw PartnerErrors.unprocessable("剧名不能为空");
            }
            if (!title.equals(drama.getTitle())) {
                DramaRow other = dramas.findOwnedByTitleExcluding(supplierId, title, drama.getId());
                if (other != null) {
                    Map<String, Object> detail = PartnerErrors.detail("title_exists", "你已有同名剧");
                    detail.put("drama_id", other.getId());
                    throw PartnerErrors.structured(409, detail);
                }
                drama.setTitle(title);
                changed = true;
            }
        }
        if (PartnerBody.present(body, "synopsis") && !PartnerBody.isNull(body, "synopsis")) {
            String value = PartnerText.strip(PartnerBody.optionalString(body, "synopsis", 0, 5000));
            if (!Objects.equals(value, drama.getSynopsis())) {
                drama.setSynopsis(value);
                changed = true;
            }
        }
        if (PartnerBody.present(body, "cover_url") && !PartnerBody.isNull(body, "cover_url")) {
            String value = PartnerText.strip(PartnerBody.optionalString(body, "cover_url", 0, 500));
            if (!Objects.equals(value, drama.getCoverUrl())) {
                drama.setCoverUrl(value);
                changed = true;
            }
        }
        if (PartnerBody.present(body, "country_allowlist")
                && !PartnerBody.isNull(body, "country_allowlist")) {
            String value = PartnerText.strip(
                    PartnerBody.optionalString(body, "country_allowlist", 0, 200));
            if (!Objects.equals(value, drama.getCountryAllowlist())) {
                drama.setCountryAllowlist(value);
                changed = true;
            }
        }
        if (PartnerBody.present(body, "free_episode_count")
                && !PartnerBody.isNull(body, "free_episode_count")) {
            Integer value = PartnerBody.optionalInt(body, "free_episode_count", 0, 1000);
            if (!Objects.equals(value, drama.getFreeEpisodeCount())) {
                drama.setFreeEpisodeCount(value);
                changed = true;
            }
        }
        if (PartnerBody.present(body, "age_rating")) {
            Integer value = PartnerBody.optionalInt(body, "age_rating", 0, 18);
            if (!Objects.equals(value, drama.getAgeRating())) {
                drama.setAgeRating(value);
                ageRatingSet = true;
                changed = true;
            }
        }
        boolean tagsReplaced = false;
        if (PartnerBody.present(body, "tag_ids") && !PartnerBody.isNull(body, "tag_ids")) {
            List<Long> tagIds = PartnerBody.optionalLongList(body, "tag_ids");
            replaceTags(drama.getId(), tagIds == null ? List.of() : tagIds);
            tagsReplaced = true;
        }
        if ("rejected".equals(drama.getReviewState())) {
            // Editing a rejected drama clears the verdict; it must be resubmitted.
            drama.setReviewState("none");
            drama.setReviewNote(null);
            reviewNoteSet = true;
            changed = true;
        }

        if (changed || ageRatingSet || reviewNoteSet) {
            try {
                dramas.updateDrama(drama, ageRatingSet, reviewNoteSet);
            } catch (DuplicateKeyException ex) {
                throw PartnerErrors.conflict("同名剧已存在");
            }
        }
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("fields", new ArrayList<>(provided));
        audit.partnerAction(user, "partner_drama_update", drama.getId(), auditPayload);
        return support.dramaOut(dramas.findById(dramaId), true);
    }

    private void replaceTags(long dramaId, List<Long> tagIds) {
        List<TagRow> rows = tagIds.isEmpty() ? List.of() : tags.findByIds(tagIds);
        if (rows.size() != new HashSet<>(tagIds).size()) {
            throw PartnerErrors.unprocessable("存在无效的标签");
        }
        for (TagRow tag : rows) {
            if (!"enabled".equals(tag.getStatus())) {
                throw PartnerErrors.unprocessable("存在已下线的标签");
            }
        }
        dramas.deleteDramaTags(dramaId);
        for (TagRow tag : rows) {
            dramas.insertDramaTag(dramaId, tag.getId());
        }
    }

    // ---- cover ----

    @Transactional
    public Map<String, Object> uploadCover(AdminUserRow user, long dramaId, MultipartFile file) {
        // Pydantic validates the required `file` field before the handler body runs.
        if (file == null) {
            throw PartnerErrors.bodyValidation("file", "Field required", "missing");
        }
        DramaRow drama = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        support.requireEditable(drama);
        if (!bunny.live()) {
            throw ApiException.serviceUnavailable("图片存储未配置，可先填写封面链接");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read cover upload", ex);
        }
        if (data.length == 0 || data.length > PartnerLimits.COVER_MAX_BYTES) {
            throw PartnerErrors.unprocessable("封面大小需在 1 字节到 5MB 之间");
        }
        String ext = sniffCoverExt(data);
        if (ext == null) {
            throw PartnerErrors.unprocessable("封面只支持 JPG、PNG、WebP");
        }
        String remotePath = coverRemote(drama.getId(), ext);
        try {
            bunny.storageUpload(remotePath, data, PartnerLimits.COVER_MIME.get(ext));
        } catch (PartnerBunnyException ex) {
            throw new ApiException(502, "封面上传失败：" + ex.getMessage());
        }
        String coverUrl = bunny.imageUrl(remotePath) + "?v=" + PartnerText.epochTimestamp();
        dramas.updateCoverUrl(drama.getId(), coverUrl);
        audit.partnerAction(user, "partner_cover_upload", drama.getId(), Map.of());
        purgeCover(drama.getId(), ext);
        forgetOtherCovers(drama.getId(), ext);
        return Map.of("cover_url", coverUrl);
    }

    private static String coverRemote(long dramaId, String ext) {
        return "covers/" + dramaId + "/cover." + ext;
    }

    /** Trust file bytes, not the browser-supplied Content-Type. */
    private static String sniffCoverExt(byte[] data) {
        if (data.length >= 3 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G' && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "png";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private void bestEffort(Runnable action) {
        if (!bunny.live()) {
            return;
        }
        try {
            action.run();
        } catch (PartnerBunnyException ex) {
            // best effort: a CDN hiccup must not fail the saved cover
        }
    }

    /** Drop the canonical object and any {@code ?v=} cache variant. */
    private void purgeCover(long dramaId, String ext) {
        if (!bunny.live()) {
            return;
        }
        String url;
        try {
            url = bunny.imageUrl(coverRemote(dramaId, ext));
        } catch (PartnerBunnyException ex) {
            return;
        }
        bestEffort(() -> bunny.purgeUrl(url));
        bestEffort(() -> bunny.purgeUrl(url + "*"));
    }

    private void forgetOtherCovers(long dramaId, String keepExt) {
        for (String ext : PartnerLimits.COVER_EXTS) {
            if (ext.equals(keepExt)) {
                continue;
            }
            bestEffort(() -> bunny.storageDelete(coverRemote(dramaId, ext)));
            purgeCover(dramaId, ext);
        }
    }

    // ---- submit / delete ----

    @Transactional
    public Map<String, Object> submit(AdminUserRow user, long dramaId) {
        DramaRow drama = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        if (!"draft".equals(drama.getStatus())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("drama_locked", "只有草稿可以提交审核"));
        }
        if (!"none".equals(drama.getReviewState()) && !"rejected".equals(drama.getReviewState())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("drama_in_review", "已提交，等待审核"));
        }
        if (!PartnerText.truthy(drama.getCoverUrl())) {
            throw PartnerErrors.structured(422,
                    PartnerErrors.detail("cover_required", "请先上传封面"));
        }
        if (PartnerText.pyLen(PartnerText.strip(drama.getSynopsis())) < 30) {
            throw PartnerErrors.structured(422,
                    PartnerErrors.detail("synopsis_too_short", "简介至少 30 字"));
        }
        List<EpisodeRow> rows = episodes.listByDrama(drama.getId());
        if (rows.isEmpty()) {
            throw PartnerErrors.structured(422,
                    PartnerErrors.detail("episodes_required", "至少上传 1 集"));
        }
        List<Integer> pending = new ArrayList<>();
        for (EpisodeRow row : rows) {
            if (!"in_review".equals(row.getStatus())) {
                pending.add(row.getEpisodeNumber());
            }
        }
        if (!pending.isEmpty()) {
            Map<String, Object> detail = PartnerErrors.detail("episodes_not_ready",
                    "第 " + PartnerText.joinInts(pending) + " 集尚未完成转码");
            detail.put("episodes", pending);
            throw PartnerErrors.structured(422, detail);
        }
        int floor = Math.min(PartnerLimits.MIN_FREE_EPISODES, rows.size());
        int freeEpisodes = drama.getFreeEpisodeCount() == null ? 0 : drama.getFreeEpisodeCount();
        if (freeEpisodes < floor || freeEpisodes > rows.size()) {
            Map<String, Object> detail = PartnerErrors.detail("free_episode_count_out_of_range",
                    "免费集数需在 " + floor + "–" + rows.size() + " 之间");
            detail.put("floor", floor);
            detail.put("max", rows.size());
            throw PartnerErrors.structured(422, detail);
        }
        dramas.updateSubmission(drama.getId(), "pending", Times.now());
        audit.partnerAction(user, "partner_drama_submit", drama.getId(), Map.of("episodes", rows.size()));
        return support.dramaOut(dramas.findById(dramaId), true);
    }

    /** Suppliers may delete their own drafts; anything with video or review is locked. */
    @Transactional
    public void delete(AdminUserRow user, long dramaId) {
        DramaRow drama = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        support.requireEditable(drama);
        List<EpisodeRow> rows = episodes.listByDrama(dramaId);
        for (EpisodeRow row : rows) {
            if (!"not_uploaded".equals(row.getStatus())) {
                throw PartnerErrors.structured(409,
                        PartnerErrors.detail("drama_has_video", "已上传视频的剧集不能删除"));
            }
        }
        String title = drama.getTitle();
        for (EpisodeRow row : rows) {
            episodes.deleteEpisode(row.getId());
        }
        dramas.deleteDramaTags(dramaId);
        dramas.deleteDrama(dramaId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("drama_id", dramaId);
        audit.partnerAction(user, "partner_drama_delete", null, payload);
    }

    private static Object countValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
