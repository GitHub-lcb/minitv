package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.partner.mapper.PartnerDramaMapper;
import us.minitv.partner.mapper.PartnerEpisodeMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Episode CRUD and supplier preview, ported from {@code partner_router}
 * ("---- episodes ----").
 */
@Service
public class PartnerEpisodeService {

    private final PartnerEpisodeMapper episodes;
    private final PartnerDramaMapper dramas;
    private final PartnerContentSupport support;
    private final PartnerAuditService audit;
    private final PartnerBunny bunny;

    public PartnerEpisodeService(PartnerEpisodeMapper episodes,
                                 PartnerDramaMapper dramas,
                                 PartnerContentSupport support,
                                 PartnerAuditService audit,
                                 PartnerBunny bunny) {
        this.episodes = episodes;
        this.dramas = dramas;
        this.support = support;
        this.audit = audit;
        this.bunny = bunny;
    }

    @Transactional
    public Map<String, Object> create(AdminUserRow user, long dramaId, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        Integer requestedNumber = PartnerBody.optionalInt(body, "episode_number", 1,
                PartnerLimits.MAX_EPISODES);
        String requestedTitle = PartnerBody.stringOr(body, "title", "", 200);
        DramaRow drama = support.requireOwnedDrama(user.getSupplierId(), dramaId);
        support.requireEditable(drama);

        long count = episodes.countByDrama(drama.getId());
        if (count >= PartnerLimits.MAX_EPISODES) {
            throw PartnerErrors.unprocessable("单部剧最多 " + PartnerLimits.MAX_EPISODES + " 集");
        }
        int number;
        if (requestedNumber == null) {
            Integer max = episodes.maxEpisodeNumber(drama.getId());
            number = (max == null ? 0 : max) + 1;
        } else {
            number = requestedNumber;
        }
        if (episodes.findIdByDramaNumber(drama.getId(), number) != null) {
            throw PartnerErrors.conflict("第 " + number + " 集已存在");
        }
        String title = PartnerText.strip(requestedTitle);
        if (!PartnerText.truthy(title)) {
            title = "第 " + number + " 集";
        }
        EpisodeRow row = new EpisodeRow();
        row.setDramaId(drama.getId());
        row.setEpisodeNumber(number);
        row.setTitle(title);
        row.setStatus("not_uploaded");
        row.setDurationSeconds(0);
        row.setBunnyVideoId("");
        row.setSubtitleUrl("");
        row.setWidth(0);
        row.setHeight(0);
        row.setSizeBytes(0L);
        row.setMime("");
        try {
            episodes.insertEpisode(row);
        } catch (DuplicateKeyException ex) {
            throw PartnerErrors.conflict("第 " + number + " 集已存在");
        }
        audit.partnerAction(user, "partner_episode_create", drama.getId(), Map.of("episode", number));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        payload.put("episode_number", row.getEpisodeNumber());
        payload.put("title", row.getTitle());
        payload.put("status", row.getStatus());
        return payload;
    }

    @Transactional
    public Map<String, Object> update(AdminUserRow user, long episodeId, JsonNode bodyNode) {
        JsonNode body = PartnerBody.require(bodyNode);
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        DramaRow drama = dramas.findById(episode.getDramaId());
        support.requireEditable(drama);

        Integer number = PartnerBody.optionalInt(body, "episode_number", 1,
                PartnerLimits.MAX_EPISODES);
        String title = PartnerBody.optionalString(body, "title", 1, 200);

        boolean changed = false;
        if (number != null && number != episode.getEpisodeNumber()) {
            if (episodes.findOtherIdByDramaNumber(episode.getDramaId(), number, episode.getId()) != null) {
                throw PartnerErrors.conflict("第 " + number + " 集已存在");
            }
            episode.setEpisodeNumber(number);
            changed = true;
        }
        if (title != null) {
            String stripped = PartnerText.strip(title);
            if (!stripped.equals(episode.getTitle())) {
                episode.setTitle(stripped);
                changed = true;
            }
        }
        if (changed) {
            try {
                episodes.updateEpisode(episode);
            } catch (DuplicateKeyException ex) {
                throw PartnerErrors.conflict("第 " + episode.getEpisodeNumber() + " 集已存在");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", episode.getId());
        payload.put("title", episode.getTitle());
        payload.put("episode_number", episode.getEpisodeNumber());
        return payload;
    }

    @Transactional
    public void delete(AdminUserRow user, long episodeId) {
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        DramaRow drama = dramas.findById(episode.getDramaId());
        support.requireEditable(drama);
        if (!"not_uploaded".equals(episode.getStatus())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("episode_has_video", "已上传视频的分集不能删除"));
        }
        int number = episode.getEpisodeNumber();
        episodes.deleteEpisode(episode.getId());
        audit.partnerAction(user, "partner_episode_delete", drama.getId(), Map.of("episode", number));
    }

    /**
     * Signed Bunny player URL so a supplier can review its own episode.
     * Read-only and ownership-scoped, and deliberately available in every state.
     */
    public Map<String, Object> previewUrl(AdminUserRow user, long episodeId) {
        EpisodeRow episode = support.requireOwnedEpisode(user.getSupplierId(), episodeId);
        if (!PartnerText.truthy(episode.getBunnyVideoId())) {
            throw PartnerErrors.structured(409,
                    PartnerErrors.detail("no_video", "该集还没有可预览的视频"));
        }
        Map<String, Object> signed;
        try {
            signed = bunny.streamEmbedUrl(episode.getBunnyVideoId(), 3600);
        } catch (PartnerBunnyException ex) {
            throw ApiException.serviceUnavailable("preview signing unavailable: " + ex.getMessage());
        }
        signed.put("episode_id", episode.getId());
        signed.put("episode_number", episode.getEpisodeNumber());
        signed.put("video_id", episode.getBunnyVideoId());
        return signed;
    }
}
