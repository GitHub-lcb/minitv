package us.minitv.partner;

import org.springframework.stereotype.Component;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.MediaUploadRow;
import us.minitv.partner.mapper.PartnerDramaMapper;
import us.minitv.partner.mapper.PartnerEpisodeMapper;
import us.minitv.partner.mapper.PartnerMediaUploadMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared read/guard helpers for the supplier surface, ported from the private
 * helpers in {@code partner_router} ({@code _partner_drama_or_404},
 * {@code _episode_or_404}, {@code _editable}, {@code _drama_out}).
 */
@Component
public class PartnerContentSupport {

    private final PartnerDramaMapper dramas;
    private final PartnerEpisodeMapper episodes;
    private final PartnerMediaUploadMapper uploads;

    public PartnerContentSupport(PartnerDramaMapper dramas,
                                 PartnerEpisodeMapper episodes,
                                 PartnerMediaUploadMapper uploads) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.uploads = uploads;
    }

    /** Ownership first, state later: a 403 would confirm the row exists. */
    public DramaRow requireOwnedDrama(long supplierId, long dramaId) {
        DramaRow row = dramas.findOwned(dramaId, supplierId);
        if (row == null) {
            throw PartnerErrors.notFound("drama not found");
        }
        return row;
    }

    public EpisodeRow requireOwnedEpisode(long supplierId, long episodeId) {
        EpisodeRow row = episodes.findOwned(episodeId, supplierId);
        if (row == null) {
            throw PartnerErrors.notFound("episode not found");
        }
        return row;
    }

    /** Suppliers may only touch drafts (including a rejected draft under repair). */
    public void requireEditable(DramaRow drama) {
        if (drama == null) {
            throw PartnerErrors.notFound("drama not found");
        }
        if (!"draft".equals(drama.getStatus())) {
            Map<String, Object> detail = PartnerErrors.detail("drama_locked",
                    "剧集当前状态 " + drama.getStatus() + "，上剧方只能编辑草稿");
            throw PartnerErrors.structured(409, detail);
        }
        if ("pending".equals(drama.getReviewState())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("drama_in_review", "审核中不可修改"));
        }
    }

    public DramaRow requireDrama(long dramaId) {
        DramaRow row = dramas.findById(dramaId);
        if (row == null) {
            throw PartnerErrors.notFound("drama not found");
        }
        return row;
    }

    public int episodeCount(long dramaId) {
        return (int) episodes.countByDrama(dramaId);
    }

    /** Apply an episode status transition in memory and in the database. */
    public void transitionEpisode(EpisodeRow episode, String toStatus) {
        PartnerTransitions.requireEpisode(episode.getStatus(), toStatus);
        episodes.updateStatus(episode.getId(), toStatus);
        episode.setStatus(toStatus);
    }

    public MediaUploadRow latestUpload(long episodeId) {
        return uploads.findLatestByEpisode(episodeId);
    }

    /** {@code _drama_out}: the exact supplier-facing drama payload. */
    public Map<String, Object> dramaOut(DramaRow drama, boolean withEpisodes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", drama.getId());
        payload.put("title", drama.getTitle());
        payload.put("synopsis", drama.getSynopsis());
        payload.put("cover_url", drama.getCoverUrl());
        payload.put("status", drama.getStatus());
        payload.put("review_state", drama.getReviewState());
        payload.put("review_note", drama.getReviewNote());
        payload.put("free_episode_count", drama.getFreeEpisodeCount());
        // Read-only on this surface: pricing belongs to the platform.
        payload.put("coin_price_per_episode", drama.getCoinPricePerEpisode());
        payload.put("play_count", drama.getPlayCount());
        payload.put("episode_count", episodeCount(drama.getId()));
        payload.put("submitted_at", drama.getSubmittedAt());
        payload.put("reviewed_at", drama.getReviewedAt());
        payload.put("updated_at", drama.getUpdatedAt());
        payload.put("editable", "draft".equals(drama.getStatus())
                && !"pending".equals(drama.getReviewState()));
        if (withEpisodes) {
            List<EpisodeRow> rows = episodes.listByDrama(drama.getId());
            Map<Long, String> uploadStatuses = new HashMap<>();
            if (!rows.isEmpty()) {
                List<Long> ids = new ArrayList<>();
                for (EpisodeRow row : rows) {
                    ids.add(row.getId());
                }
                for (Map<String, Object> item : uploads.statusesByEpisodes(ids)) {
                    Object episodeId = item.get("episodeId");
                    if (episodeId instanceof Number number) {
                        uploadStatuses.put(number.longValue(), item.get("status") instanceof String text ? text : null);
                    }
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (EpisodeRow row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.getId());
                item.put("episode_number", row.getEpisodeNumber());
                item.put("title", row.getTitle());
                item.put("status", row.getStatus());
                item.put("upload_status", uploadStatuses.get(row.getId()));
                item.put("duration_seconds", row.getDurationSeconds());
                item.put("size_bytes", row.getSizeBytes());
                // Drives the row-level 预览 button: no Bunny video means nothing to sign.
                item.put("has_video", PartnerText.truthy(row.getBunnyVideoId()));
                out.add(item);
            }
            payload.put("episodes", out);
            payload.put("tag_ids", dramas.tagIds(drama.getId()));
        }
        return payload;
    }
}
