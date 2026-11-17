package us.minitv.admin.core.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreDramaMapper;
import us.minitv.admin.core.mapper.AdminCoreEpisodeMapper;
import us.minitv.admin.core.mapper.AdminCoreTagMapper;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.TagRow;
import us.minitv.ops.row.AdminUserRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Ops drama management, the tag library and the top search box
 * (ops/router.py "dramas", "tags" and "top search" sections).
 */
@Service
public class AdminCoreContentService {

    private static final List<String> DRAMA_STATUS_BUCKETS =
            List.of("draft", "in_review", "published", "unpublished");

    private final AdminCoreDramaMapper dramas;
    private final AdminCoreTagMapper tags;
    private final AdminCoreEpisodeMapper episodes;

    public AdminCoreContentService(AdminCoreDramaMapper dramas, AdminCoreTagMapper tags,
                                   AdminCoreEpisodeMapper episodes) {
        this.dramas = dramas;
        this.tags = tags;
        this.episodes = episodes;
    }

    // ---- dramas ----

    public Map<String, Object> listDramas(String status, String q, Long tagId, int page, int pageSize) {
        AdminCoreSupport.paging(page, pageSize);
        String statusFilter = status == null || status.isEmpty() ? null : status;
        String qFilter = q == null || q.isEmpty() ? null : q;
        long total = dramas.count(statusFilter, qFilter, tagId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaRow row : dramas.list(statusFilter, qFilter, tagId, pageSize, (page - 1) * pageSize)) {
            items.add(dramaOut(row));
        }
        Map<String, Long> grouped = new HashMap<>();
        for (Map<String, Object> row : dramas.statusCounts()) {
            Object key = row.get("status");
            Object count = row.get("n");
            if (key instanceof String name && count instanceof Number number) {
                grouped.put(name, number.longValue());
            }
        }
        long all = 0;
        Map<String, Object> statusCounts = new LinkedHashMap<>();
        for (String bucket : DRAMA_STATUS_BUCKETS) {
            long count = grouped.getOrDefault(bucket, 0L);
            statusCounts.put(bucket, count);
            all += count;
        }
        Map<String, Object> withAll = new LinkedHashMap<>();
        withAll.put("all", all);
        withAll.putAll(statusCounts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("status_counts", withAll);
        out.put("items", items);
        return out;
    }

    @Transactional
    public Map<String, Object> createDrama(AdminUserRow actor, String title) {
        AdminCoreSupport.stringLength("title", title, 1, 200);
        DramaRow row = new DramaRow();
        row.setTitle(title);
        // New dramas always start as drafts; the other defaults mirror content.models.Drama.
        row.setSynopsis("");
        row.setCoverUrl("");
        row.setFreeEpisodeCount(0);
        row.setCoinPricePerEpisode(0);
        row.setStatus("draft");
        row.setCountryAllowlist("");
        row.setPlayCount(0L);
        row.setReviewState("none");
        try {
            dramas.insert(row);
        } catch (DuplicateKeyException ex) {
            // A machine caller (CLI/Agent) retries on timeout; 409 + the existing id
            // lets it distinguish "created" from "not created" and upsert.
            DramaRow existing = dramas.findByTitleFirst(title);
            Map<String, Object> detail = ApiException.detail("title_exists", "同名剧已存在");
            detail.put("drama_id", existing == null ? null : existing.getId());
            throw new ApiException(409, detail);
        }
        return dramaOut(dramas.findById(row.getId()));
    }

    public Map<String, Object> getDrama(long dramaId) {
        DramaRow row = dramas.findById(dramaId);
        if (row == null) {
            throw ApiException.notFound("drama not found");
        }
        return dramaOut(row);
    }

    @Transactional
    public Map<String, Object> updateDrama(AdminUserRow actor, long dramaId, Map<String, Object> body) {
        DramaRow row = dramas.findById(dramaId);
        if (row == null) {
            throw ApiException.notFound("drama not found");
        }
        // Pydantic validates the whole body before the handler runs.
        String title = AdminCoreSupport.bodyString(body, "title", 1, 200);
        String synopsis = AdminCoreSupport.bodyString(body, "synopsis", 0, 20000);
        String coverUrl = AdminCoreSupport.bodyString(body, "cover_url", 0, 500);
        Integer freeEpisodeCount = AdminCoreSupport.bodyInt(body, "free_episode_count", 0, null);
        Integer coinPricePerEpisode = AdminCoreSupport.bodyInt(body, "coin_price_per_episode", 0, null);
        List<Long> tagIds = AdminCoreSupport.bodyLongList(body, "tag_ids");

        boolean setTitle = body != null && body.containsKey("title");
        boolean setSynopsis = body != null && body.containsKey("synopsis");
        boolean setCoverUrl = body != null && body.containsKey("cover_url");
        boolean setFree = body != null && body.containsKey("free_episode_count");
        boolean setCoin = body != null && body.containsKey("coin_price_per_episode");

        List<Long> distinctTagIds = null;
        if (tagIds != null) {
            distinctTagIds = new ArrayList<>(new LinkedHashSet<>(tagIds));
            for (Long tagId : distinctTagIds) {
                if (tags.findById(tagId) == null) {
                    // Roll back and reject before touching the row, like the Python handler.
                    throw ApiException.unprocessable("unknown tag id in tag_ids");
                }
            }
        }

        if (setTitle || setSynopsis || setCoverUrl || setFree || setCoin) {
            try {
                dramas.updateFields(row.getId(), setTitle, title, setSynopsis, synopsis, setCoverUrl, coverUrl,
                        setFree, freeEpisodeCount, setCoin, coinPricePerEpisode, us.minitv.common.Times.now());
            } catch (DuplicateKeyException ex) {
                throw new ApiException(409, ApiException.detail("title_exists", "同名剧已存在"));
            }
        }
        if (distinctTagIds != null) {
            tags.deleteDramaTags(row.getId());
            for (Long tagId : distinctTagIds) {
                tags.insertDramaTag(row.getId(), tagId);
            }
        }
        return dramaOut(dramas.findById(row.getId()));
    }

    // ---- tags ----

    public Map<String, Object> listTags() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TagRow row : tags.listAll()) {
            items.add(tagOut(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    @Transactional
    public Map<String, Object> createTag(AdminUserRow actor, String nameEn, String nameZh, String category, String status) {
        AdminCoreSupport.stringLength("name_en", nameEn, 1, 64);
        String cleanNameZh = nameZh == null ? "" : nameZh;
        String cleanCategory = category == null ? "" : category;
        String cleanStatus = status == null ? "enabled" : status;
        if (cleanNameZh.length() > 64) {
            throw ApiException.unprocessable("name_zh: String should have at most 64 characters");
        }
        if (cleanCategory.length() > 32) {
            throw ApiException.unprocessable("category: String should have at most 32 characters");
        }
        if (cleanStatus.length() > 16) {
            throw ApiException.unprocessable("status: String should have at most 16 characters");
        }
        if (tags.findIdByNameEn(nameEn) != null) {
            throw ApiException.conflict("tag name_en already exists");
        }
        TagRow row = new TagRow();
        row.setNameEn(nameEn);
        row.setNameZh(cleanNameZh);
        row.setCategory(cleanCategory);
        row.setStatus(cleanStatus);
        tags.insert(row);
        return tagOut(row);
    }

    @Transactional
    public Map<String, Object> updateTag(AdminUserRow actor, long tagId, Map<String, Object> body) {
        TagRow row = tags.findById(tagId);
        if (row == null) {
            throw ApiException.notFound("tag not found");
        }
        String nameEn = AdminCoreSupport.bodyString(body, "name_en", 1, 64);
        String nameZh = AdminCoreSupport.bodyString(body, "name_zh", 0, 64);
        String category = AdminCoreSupport.bodyString(body, "category", 0, 32);
        String status = AdminCoreSupport.bodyString(body, "status", 0, 16);
        boolean setNameEn = body != null && body.containsKey("name_en");
        boolean setNameZh = body != null && body.containsKey("name_zh");
        boolean setCategory = body != null && body.containsKey("category");
        boolean setStatus = body != null && body.containsKey("status");
        if (setNameEn || setNameZh || setCategory || setStatus) {
            tags.updateFields(row.getId(), setNameEn, nameEn, setNameZh, nameZh, setCategory, category,
                    setStatus, status);
        }
        return tagOut(tags.findById(row.getId()));
    }

    @Transactional
    public Map<String, Object> deleteTag(AdminUserRow actor, long tagId) {
        TagRow row = tags.findById(tagId);
        if (row == null) {
            throw ApiException.notFound("tag not found");
        }
        List<DramaRow> affected = tags.listDramasByTag(row.getId());
        if (!affected.isEmpty()) {
            List<Map<String, Object>> affectedDramas = new ArrayList<>();
            for (DramaRow drama : affected) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", drama.getId());
                item.put("title", drama.getTitle());
                affectedDramas.add(item);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("message", "tag is in use; resolve references before deleting");
            detail.put("affected_dramas", affectedDramas);
            throw new ApiException(409, detail);
        }
        tags.deleteById(row.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    // ---- search ----

    public Map<String, Object> search(String q) {
        if (q == null || q.isEmpty()) {
            throw ApiException.unprocessable("q: String should have at least 1 character");
        }
        List<Map<String, Object>> found = new ArrayList<>();
        for (DramaRow drama : dramas.list(null, q, null, 20, 0)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", drama.getId());
            item.put("title", drama.getTitle());
            item.put("status", drama.getStatus());
            found.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dramas", found);
        // Users land in a later stage; the Python handler always returns an empty list here.
        out.put("users", new ArrayList<>());
        return out;
    }

    // ---- serialisers ----

    public Map<String, Object> dramaOut(DramaRow drama) {
        List<Map<String, Object>> tagItems = new ArrayList<>();
        for (TagRow tag : tags.listByDrama(drama.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tag.getId());
            item.put("name_en", tag.getNameEn());
            item.put("name_zh", tag.getNameZh());
            tagItems.add(item);
        }
        long episodeCount = episodes.countByDrama(drama.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", drama.getId());
        out.put("title", drama.getTitle());
        out.put("synopsis", drama.getSynopsis());
        out.put("cover_url", drama.getCoverUrl());
        out.put("status", drama.getStatus());
        out.put("free_episode_count", drama.getFreeEpisodeCount());
        out.put("coin_price_per_episode", drama.getCoinPricePerEpisode());
        out.put("tags", tagItems);
        out.put("episode_count", episodeCount);
        // Lifetime starts reported by the player; see playback.record_event.
        out.put("play_count", drama.getPlayCount());
        out.put("updated_at", drama.getUpdatedAt());
        return out;
    }

    public Map<String, Object> tagOut(TagRow tag) {
        long dramaCount = tags.countByTagId(tag.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tag.getId());
        out.put("name_en", tag.getNameEn());
        out.put("name_zh", tag.getNameZh());
        out.put("category", tag.getCategory());
        out.put("status", tag.getStatus());
        out.put("drama_count", dramaCount);
        return out;
    }
}
