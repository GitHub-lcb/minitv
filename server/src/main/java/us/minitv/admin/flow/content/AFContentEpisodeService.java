package us.minitv.admin.flow.content;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.flow.content.mapper.AFContentAuditMapper;
import us.minitv.admin.flow.content.mapper.AFContentCdnPurgeMapper;
import us.minitv.admin.flow.content.mapper.AFContentDramaMapper;
import us.minitv.admin.flow.content.mapper.AFContentEpisodeMapper;
import us.minitv.admin.flow.content.mapper.AFContentMediaUploadMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.ExternalVideoSource;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.security.RedisStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Admin episode endpoints, ported from the first half of
 * {@code backend/app/modules/ops/flow_router.py}.
 */
@Service
public class AFContentEpisodeService {

    private final AFContentEpisodeMapper episodes;
    private final AFContentDramaMapper dramas;
    private final AFContentMediaUploadMapper uploads;
    private final AFContentCdnPurgeMapper cdnPurges;
    private final AFContentAuditMapper audits;
    private final AFContentUploadService uploadService;
    private final AFContentBunny bunny;
    private final RedisStore redis;

    public AFContentEpisodeService(AFContentEpisodeMapper episodes,
                                   AFContentDramaMapper dramas,
                                   AFContentMediaUploadMapper uploads,
                                   AFContentCdnPurgeMapper cdnPurges,
                                   AFContentAuditMapper audits,
                                   AFContentUploadService uploadService,
                                   AFContentBunny bunny,
                                   RedisStore redis) {
        this.episodes = episodes;
        this.dramas = dramas;
        this.uploads = uploads;
        this.cdnPurges = cdnPurges;
        this.audits = audits;
        this.uploadService = uploadService;
        this.bunny = bunny;
        this.redis = redis;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    EpisodeRow requireEpisode(long episodeId) {
        EpisodeRow episode = episodes.findById(episodeId);
        if (episode == null) {
            throw new ApiException(404, "episode not found");
        }
        return episode;
    }

    DramaRow requireDrama(long dramaId) {
        DramaRow drama = dramas.findById(dramaId);
        if (drama == null) {
            throw new ApiException(404, "drama not found");
        }
        return drama;
    }

    // ------------------------------------------------------------------
    // Create / list
    // ------------------------------------------------------------------

    /** {@code create_episode}: one row per episode number, duplicate names rejected. */
    @Transactional
    public Map<String, Object> createEpisode(long dramaId, AFContentAdminDtos.EpisodeIn body) {
        requireDrama(dramaId);
        int number = body.episodeNumber();
        if (episodes.findDuplicateId(dramaId, number) != null) {
            throw new ApiException(409, duplicateMessage(number));
        }
        String rawTitle = body.title() == null ? "" : body.title();
        String title = rawTitle.strip();
        if (title.isEmpty()) {
            title = "Episode " + number;
        }
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        ExternalVideoSource.Source source = ExternalVideoSource.normalise(
                body.videoProvider(), body.externalVideoId(), body.externalUrl(), body.aspectRatio());
        boolean external = ExternalVideoSource.isExternal(source.provider());
        EpisodeRow row = newEpisode(dramaId, number, title);
        row.setVideoProvider(source.provider());
        row.setExternalVideoId(source.videoId());
        row.setExternalUrl(source.url());
        row.setAspectRatio(source.aspectRatio());
        if (external) {
            // No transcode step: the platform/player owns the bytes, so the row
            // enters review immediately and skips the upload session.
            row.setStatus("in_review");
            row.setDurationSeconds(body.durationSeconds() == null ? 0 : body.durationSeconds());
        } else {
            row.setDurationSeconds(body.durationSeconds() == null ? 0 : body.durationSeconds());
        }
        episodes.insert(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("episode_number", row.getEpisodeNumber());
        result.put("title", row.getTitle());
        result.put("status", row.getStatus());
        result.put("video_provider", row.getVideoProvider());
        return result;
    }

    /** {@code bulk_episodes}: create every missing number in 1..count. */
    @Transactional
    public Map<String, Object> bulkEpisodes(long dramaId, AFContentAdminDtos.BulkIn body) {
        requireDrama(dramaId);
        Set<Integer> existing = new HashSet<>(episodes.listNumbersByDrama(dramaId));
        List<Integer> created = new ArrayList<>();
        int count = body.count();
        for (int number = 1; number <= count; number++) {
            if (existing.contains(number)) {
                continue;
            }
            episodes.insert(newEpisode(dramaId, number, "Episode " + number));
            created.add(number);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("total", count);
        return result;
    }

    /** {@code list_episodes}: paged list, plus the real-mode transcode reconcile pass. */
    @Transactional
    public Map<String, Object> listEpisodes(long dramaId, String status, String q, int page, int pageSize) {
        String statusFilter = status == null || status.isEmpty() ? null : status;
        String qFilter = q == null || q.isEmpty() ? null : q;
        int numberFilter = qFilter == null ? -1 : maybeInt(qFilter);
        long total = episodes.countPage(dramaId, statusFilter, qFilter, numberFilter);
        List<EpisodeRow> rows = episodes.page(dramaId, statusFilter, qFilter, numberFilter,
                pageSize, (page - 1) * pageSize);
        // Opening the list is when an operator notices a stuck "transcoding" badge:
        // one library listing flips finished encodes without a manual sync click.
        if (bunny.live()) {
            boolean anyTranscoding = rows.stream().anyMatch(row ->
                    "transcoding".equals(row.getStatus()) && !safe(row.getBunnyVideoId()).isEmpty());
            if (anyTranscoding) {
                Map<String, JsonNode> remoteByGuid = uploadService.cachedLibrary();
                for (EpisodeRow row : rows) {
                    if (!"transcoding".equals(row.getStatus())) {
                        continue;
                    }
                    JsonNode remote = remoteByGuid.get(safe(row.getBunnyVideoId()));
                    if (remote == null) {
                        continue;
                    }
                    try {
                        uploadService.applyRemoteVideo(row, remote);
                    } catch (AFContentTransitions.IllegalTransition ignored) {
                        // leave the row pending; the next pass retries
                    }
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (EpisodeRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("episode_number", row.getEpisodeNumber());
            item.put("title", row.getTitle());
            item.put("status", row.getStatus());
            item.put("duration_seconds", row.getDurationSeconds());
            item.put("bunny_video_id", row.getBunnyVideoId());
            item.put("video_provider", row.getVideoProvider());
            item.put("external_video_id", row.getExternalVideoId());
            item.put("external_url", row.getExternalUrl());
            item.put("aspect_ratio", row.getAspectRatio());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("items", items);
        return result;
    }

    /** {@code episode_preview_url}: short-lived Bunny Player URLs for an admin preview. */
    public Map<String, Object> previewUrl(long episodeId) {
        EpisodeRow episode = requireEpisode(episodeId);
        String provider = safe(episode.getVideoProvider());
        if (ExternalVideoSource.isExternal(provider)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("episode_id", episode.getId());
            result.put("episode_number", episode.getEpisodeNumber());
            result.put("type", ExternalVideoSource.typeOf(provider));
            result.put("provider", provider);
            result.put("aspect_ratio", episode.getAspectRatio());
            if ("embed".equals(ExternalVideoSource.typeOf(provider))) {
                result.put("embed_url", ExternalVideoSource.embedUrl(provider,
                        episode.getExternalVideoId(), episode.getExternalUrl()));
            }
            result.put("url", episode.getExternalUrl());
            return result;
        }
        String videoId = safe(episode.getBunnyVideoId());
        if (videoId.isEmpty()) {
            throw new ApiException(409, "episode has no Bunny video");
        }
        Map<String, Object> signed;
        try {
            signed = bunny.streamEmbedUrl(videoId, 3600);
        } catch (AFContentBunny.BunnyError exc) {
            throw new ApiException(503, "preview signing unavailable: " + exc.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>(signed);
        result.put("episode_id", episode.getId());
        result.put("episode_number", episode.getEpisodeNumber());
        result.put("video_id", videoId);
        return result;
    }

    // ------------------------------------------------------------------
    // Metadata edit / publish / bulk status / delete
    // ------------------------------------------------------------------

    /** {@code update_episode}: partial metadata edit (title / episode number / source). */
    @Transactional
    public Map<String, Object> updateEpisode(long episodeId, AFContentAdminDtos.EpisodeUpdateIn body) {
        EpisodeRow episode = requireEpisode(episodeId);
        boolean sourceTouched = body.videoProvider() != null || body.externalVideoId() != null
                || body.externalUrl() != null || body.aspectRatio() != null;
        if (body.title() == null && body.episodeNumber() == null && !sourceTouched) {
            throw ApiException.unprocessable("没有需要更新的字段");
        }
        if (sourceTouched) {
            String provider = body.videoProvider() != null ? body.videoProvider() : episode.getVideoProvider();
            String videoId = body.externalVideoId() != null ? body.externalVideoId() : episode.getExternalVideoId();
            String url = body.externalUrl() != null ? body.externalUrl() : episode.getExternalUrl();
            String aspect = body.aspectRatio() != null ? body.aspectRatio() : episode.getAspectRatio();
            ExternalVideoSource.Source source = ExternalVideoSource.normalise(provider, videoId, url, aspect);
            boolean external = ExternalVideoSource.isExternal(source.provider());
            String status;
            String bunnyId;
            if (external) {
                // Switching to an external source drops the Bunny object. Rows that
                // were never playable enter review; published rows keep their status.
                bunnyId = "";
                status = "published".equals(episode.getStatus()) || "unpublished".equals(episode.getStatus())
                        ? episode.getStatus() : "in_review";
            } else {
                bunnyId = episode.getBunnyVideoId() == null ? "" : episode.getBunnyVideoId();
                status = ExternalVideoSource.isExternal(episode.getVideoProvider())
                        ? "not_uploaded" : episode.getStatus();
            }
            episodes.updateSource(episodeId, source.provider(), source.videoId(), source.url(),
                    source.aspectRatio(), status, bunnyId);
            episode.setVideoProvider(source.provider());
            episode.setExternalVideoId(source.videoId());
            episode.setExternalUrl(source.url());
            episode.setAspectRatio(source.aspectRatio());
            episode.setStatus(status);
            episode.setBunnyVideoId(bunnyId);
        }
        if (body.title() != null) {
            String title = body.title().strip();
            if (title.isEmpty()) {
                throw ApiException.unprocessable("标题不能为空");
            }
            episodes.updateTitle(episodeId, title);
            episode.setTitle(title);
        }
        if (body.episodeNumber() != null && !body.episodeNumber().equals(episode.getEpisodeNumber())) {
            int number = body.episodeNumber();
            if (episodes.findDuplicateIdExcluding(episode.getDramaId(), number, episode.getId()) != null) {
                throw new ApiException(409, duplicateMessage(number));
            }
            try {
                episodes.updateEpisodeNumber(episodeId, number);
            } catch (DuplicateKeyException exc) {
                // Race with a concurrent create/rename of the same number.
                throw new ApiException(409, duplicateMessage(number));
            }
            episode.setEpisodeNumber(number);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", episode.getId());
        result.put("episode_number", episode.getEpisodeNumber());
        result.put("title", episode.getTitle());
        result.put("status", episode.getStatus());
        return result;
    }

    /** {@code publish_one}. */
    @Transactional
    public Map<String, Object> publishEpisode(long episodeId) {
        EpisodeRow episode = requireEpisode(episodeId);
        try {
            uploadService.applyEpisodeTransition(episode, "published");
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw new ApiException(409, exc.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", episode.getStatus());
        return result;
    }

    /** {@code unpublish_one}: the video object stays intact. */
    @Transactional
    public Map<String, Object> unpublishEpisode(long episodeId) {
        EpisodeRow episode = requireEpisode(episodeId);
        try {
            uploadService.applyEpisodeTransition(episode, "unpublished");
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw new ApiException(409, exc.getMessage());
        }
        bumpGeneration(episodeId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", episode.getStatus());
        return result;
    }

    /** {@code bulk_episode_status}: partial success, unusable rows are reported. */
    @Transactional
    public Map<String, Object> bulkStatus(AFContentAdminDtos.EpisodeBulkStatusIn body) {
        String target = "publish".equals(body.action()) ? "published" : "unpublished";
        List<EpisodeRow> rows = episodes.listByIds(body.episodeIds());
        Set<Long> found = new LinkedHashSet<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (EpisodeRow episode : rows) {
            found.add(episode.getId());
            boolean hasSource = !safe(episode.getBunnyVideoId()).isEmpty()
                    || ExternalVideoSource.isExternal(episode.getVideoProvider());
            if ("published".equals(target) && !hasSource) {
                skipped.add(skippedRow(episode.getId(), episode.getStatus(), "no_video"));
                continue;
            }
            try {
                uploadService.applyEpisodeTransition(episode, target);
            } catch (AFContentTransitions.IllegalTransition exc) {
                skipped.add(skippedRow(episode.getId(), episode.getStatus(), "invalid_transition"));
                continue;
            }
            if ("unpublished".equals(target)) {
                bumpGeneration(episode.getId());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", episode.getId());
            row.put("episode_number", episode.getEpisodeNumber());
            row.put("status", episode.getStatus());
            updated.add(row);
        }
        Set<Long> missing = new TreeSet<>(body.episodeIds());
        missing.removeAll(found);
        for (Long missingId : missing) {
            skipped.add(skippedRow(missingId, null, "not_found"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", body.action());
        result.put("updated", updated);
        result.put("skipped", skipped);
        return result;
    }

    /** {@code delete_episode}: empty shells only (not_uploaded / transcode_failed). */
    @Transactional
    public void deleteEpisode(long episodeId, long actorId) {
        EpisodeRow episode = requireEpisode(episodeId);
        String status = episode.getStatus();
        boolean external = ExternalVideoSource.isExternal(episode.getVideoProvider());
        // External rows have no bytes to orphan: they can be removed unless they
        // are live (published). Bunny rows stay restricted to empty shells.
        boolean deletable = external
                ? !"published".equals(status) && !"transcoding".equals(status)
                : "not_uploaded".equals(status) || "transcode_failed".equals(status);
        if (!deletable) {
            throw new ApiException(409, ApiException.detail("episode_not_empty",
                    "第 " + episode.getEpisodeNumber() + " 集是 " + status + "，只有空壳可以删；请先下架"));
        }
        bumpGeneration(episode.getId());
        uploads.deleteByEpisodeId(episode.getId());
        cdnPurges.deleteByEpisodeId(episode.getId());
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("episode_delete");
        log.setTargetType("episode");
        log.setTargetId(episode.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("drama_id", episode.getDramaId());
        payload.put("episode_number", episode.getEpisodeNumber());
        payload.put("status", status);
        log.setPayload(Json.write(payload));
        audits.insert(log);
        try {
            episodes.deleteById(episode.getId());
        } catch (DataIntegrityViolationException exc) {
            // Playback / comment / wallet rows reference the episode. Deleting a
            // row that was ever live would orphan financial or analytic records,
            // so refuse with an actionable message instead of a 500.
            throw new ApiException(409, ApiException.detail("episode_has_records",
                    "第 " + episode.getEpisodeNumber() + " 集已有播放/互动/账务数据，不能删除；请下架保留"));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EpisodeRow newEpisode(long dramaId, int number, String title) {
        EpisodeRow row = new EpisodeRow();
        row.setDramaId(dramaId);
        row.setEpisodeNumber(number);
        row.setTitle(title);
        row.setDurationSeconds(0);
        row.setBunnyVideoId("");
        row.setVideoProvider(ExternalVideoSource.BUNNY);
        row.setExternalVideoId("");
        row.setExternalUrl("");
        row.setAspectRatio("");
        row.setSubtitleUrl("");
        row.setWidth(0);
        row.setHeight(0);
        row.setSizeBytes(0L);
        row.setMime("");
        row.setStatus("not_uploaded");
        return row;
    }

    private Map<String, Object> skippedRow(Long id, String status, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("status", status);
        row.put("reason", reason);
        return row;
    }

    private void bumpGeneration(long episodeId) {
        redis.increment("playback_gen:" + episodeId);
    }

    private static String duplicateMessage(int number) {
        return "第 " + number + " 集已存在，请换一个集数";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Python {@code _maybe_int}: unparsable input filters on -1 instead of erroring. */
    static int maybeInt(String value) {
        if (value == null) {
            return -1;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return -1;
        }
        String digits = text;
        int index = 0;
        if (digits.charAt(0) == '+' || digits.charAt(0) == '-') {
            index = 1;
        }
        if (index >= digits.length()) {
            return -1;
        }
        for (int position = index; position < digits.length(); position++) {
            char ch = digits.charAt(position);
            if (!Character.isDigit(ch) && ch != '_') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(digits.replace("_", ""));
        } catch (NumberFormatException exc) {
            return -1;
        }
    }
}
