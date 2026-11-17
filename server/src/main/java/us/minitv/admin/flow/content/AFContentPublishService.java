package us.minitv.admin.flow.content;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.flow.content.mapper.AFContentAuditMapper;
import us.minitv.admin.flow.content.mapper.AFContentCascadeMapper;
import us.minitv.admin.flow.content.mapper.AFContentCdnPurgeMapper;
import us.minitv.admin.flow.content.mapper.AFContentDramaMapper;
import us.minitv.admin.flow.content.mapper.AFContentEpisodeMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.Times;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.row.CdnPurgeRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.security.RedisStore;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publish / takedown / schedule / irreversible delete, ported from
 * {@code backend/app/modules/content/publish_service.py}.
 */
@Service
public class AFContentPublishService {

    private final AFContentDramaMapper dramas;
    private final AFContentEpisodeMapper episodes;
    private final AFContentCdnPurgeMapper cdnPurges;
    private final AFContentAuditMapper audits;
    private final AFContentCascadeMapper cascades;
    private final AFContentBunny bunny;
    private final RedisStore redis;

    public AFContentPublishService(AFContentDramaMapper dramas,
                                   AFContentEpisodeMapper episodes,
                                   AFContentCdnPurgeMapper cdnPurges,
                                   AFContentAuditMapper audits,
                                   AFContentCascadeMapper cascades,
                                   AFContentBunny bunny,
                                   RedisStore redis) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.cdnPurges = cdnPurges;
        this.audits = audits;
        this.cascades = cascades;
        this.bunny = bunny;
        this.redis = redis;
    }

    /** {@code _drama_or_404}. */
    DramaRow requireDrama(long dramaId) {
        DramaRow drama = dramas.findById(dramaId);
        if (drama == null) {
            throw new ApiException(404, "drama not found");
        }
        return drama;
    }

    /** {@code change_drama_status}: guarded transition + audit + cache invalidation. */
    @Transactional
    public Map<String, Object> changeDramaStatus(long dramaId, String toStatus, long actorId) {
        DramaRow drama = requireDrama(dramaId);
        // Supplier content becomes visible only after review.
        if ("published".equals(toStatus) && drama.getSupplierId() != null
                && !"approved".equals(drama.getReviewState())) {
            throw new ApiException(409, ApiException.detail("not_approved", "上剧方剧集需审核通过后才能上架"));
        }
        try {
            AFContentTransitions.requireDramaTransition(drama.getStatus(), toStatus);
        } catch (AFContentTransitions.IllegalTransition exc) {
            throw new ApiException(409, exc.getMessage());
        }
        dramas.updateStatus(dramaId, toStatus);
        drama.setStatus(toStatus);
        if ("published".equals(toStatus)) {
            LocalDateTime publishedAt = Times.now();
            dramas.updatePublishedAt(dramaId, publishedAt);
            drama.setPublishedAt(publishedAt);
        }
        if ("unpublished".equals(toStatus)) {
            invalidateDrama(drama, "unpublish");
        }
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("drama_" + toStatus);
        log.setTargetType("drama");
        log.setTargetId(drama.getId());
        log.setPayload(Json.write(new LinkedHashMap<>()));
        audits.insert(log);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", drama.getStatus());
        return result;
    }

    /** {@code schedule_publish}: store the instant and record who scheduled it. */
    @Transactional
    public Map<String, Object> schedulePublish(long dramaId, String atRaw, long actorId) {
        LocalDateTime when = parseDateTime(atRaw);
        DramaRow drama = requireDrama(dramaId);
        dramas.updateSchedule(dramaId, when);
        drama.setScheduledPublishAt(when);
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("drama_schedule");
        log.setTargetType("drama");
        log.setTargetId(drama.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", Times.iso(when));
        log.setPayload(Json.write(payload));
        audits.insert(log);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduled_publish_at", drama.getScheduledPublishAt());
        return result;
    }

    /** {@code apply_due_schedules}: publish every drama whose slot has passed. */
    @Transactional
    public Map<String, Object> applyDueSchedules() {
        LocalDateTime now = Times.now();
        List<DramaRow> due = dramas.listDueSchedules(now);
        int count = 0;
        for (DramaRow drama : due) {
            if ("unpublished".equals(drama.getStatus())) {
                AFContentTransitions.requireDramaTransition("unpublished", "in_review");
                dramas.updateStatus(drama.getId(), "in_review");
                drama.setStatus("in_review");
            }
            AFContentTransitions.requireDramaTransition(drama.getStatus(), "published");
            dramas.updateStatus(drama.getId(), "published");
            dramas.updatePublishedAt(drama.getId(), now);
            dramas.updateSchedule(drama.getId(), null);
            drama.setStatus("published");
            drama.setPublishedAt(now);
            drama.setScheduledPublishAt(null);
            count++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", count);
        return result;
    }

    /**
     * {@code delete_drama}: confirm-gated irreversible delete of the drama and
     * every row that references its episodes. Mirrors the Python delete order.
     */
    @Transactional
    public Map<String, Object> deleteDrama(long dramaId, boolean confirm, long actorId) {
        DramaRow drama = requireDrama(dramaId);
        Map<String, Object> impact = impact(dramaId);
        if (!confirm) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("confirm_required", true);
            detail.put("message", "删除后不可恢复");
            detail.put("impact", impact);
            throw new ApiException(409, detail);
        }
        List<Long> episodeIds = episodes.listIdsByDrama(dramaId);
        if (!episodeIds.isEmpty()) {
            cascades.nullCoinLedgerEpisodeIds(episodeIds);
            cascades.deleteEntitlementsByEpisodeIds(episodeIds);
            cascades.deleteMediaUploadsByEpisodeIds(episodeIds);
            cascades.deleteCdnPurgesByEpisodeIds(episodeIds);
            cascades.deletePlaybackEventsByEpisodeIds(episodeIds);
            cascades.deletePlaybackProgressByEpisodeIds(episodeIds);
            cascades.deleteCommentsByDrama(dramaId);
            cascades.deleteEpisodesByDrama(dramaId);
        }
        cascades.deleteDramaTagsByDrama(dramaId);
        cascades.deleteDramaHomeGroupsByDrama(dramaId);
        cascades.deleteBannersByDrama(dramaId);
        cascades.deletePushRecordsByDrama(dramaId);
        AuditLogRow log = new AuditLogRow();
        log.setActorType("admin");
        log.setActorId(actorId);
        log.setAction("drama_delete");
        log.setTargetType("drama");
        log.setTargetId(drama.getId());
        log.setPayload(Json.write(impact));
        audits.insert(log);
        dramas.deleteById(dramaId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("impact", impact);
        return result;
    }

    /** {@code _impact}: what the delete would take with it. */
    Map<String, Object> impact(long dramaId) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("groups", cascades.listGroupNamesByDrama(dramaId));
        impact.put("banner_count", cascades.countBannersByDrama(dramaId));
        impact.put("push_count", cascades.countPushRecordsByDrama(dramaId));
        impact.put("episode_count", cascades.countEpisodesByDrama(dramaId));
        return impact;
    }

    /** {@code invalidate_drama}: bump every episode generation and queue a purge row. */
    private void invalidateDrama(DramaRow drama, String reason) {
        List<EpisodeRow> rows = episodes.listByDramaOrdered(drama.getId());
        for (EpisodeRow episode : rows) {
            redis.increment("playback_gen:" + episode.getId());
            CdnPurgeRow purge = new CdnPurgeRow();
            purge.setEpisodeId(episode.getId());
            purge.setDramaId(drama.getId());
            purge.setReason(reason);
            cdnPurges.insert(purge);
            String videoId = episode.getBunnyVideoId() == null ? "" : episode.getBunnyVideoId();
            if (bunny.live() && !videoId.isEmpty()) {
                try {
                    bunny.purgeUrl("https://" + bunny.streamCdnHost() + "/" + videoId + "/*");
                } catch (AFContentBunny.BunnyError ignored) {
                    // A takedown must not be blocked by a purge failure.
                }
            }
        }
    }

    /**
     * Parses the ISO-8601 body value Pydantic's {@code datetime} accepts: naive,
     * {@code Z} or an explicit offset (normalised to UTC).
     */
    static LocalDateTime parseDateTime(String raw) {
        if (raw == null) {
            throw AFContentGuards.bodyValidation("at", "datetime_type", "Input should be a valid datetime");
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            throw AFContentGuards.bodyValidation("at", "datetime_parsing", "Input should be a valid datetime");
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // try offset/zoned forms below
        }
        try {
            return OffsetDateTime.parse(text).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // try ZonedDateTime below
        }
        try {
            return ZonedDateTime.parse(text).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            throw AFContentGuards.bodyValidation("at", "datetime_parsing", "Input should be a valid datetime");
        }
    }
}
