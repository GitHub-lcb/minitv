package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.util.Map;

/** Supplier episode surface: metadata, deletion, preview signing and upload lifecycle. */
@RestController
public class PartnerEpisodeController {

    private final PartnerEpisodeService episodes;
    private final PartnerVideoUploadService uploads;

    public PartnerEpisodeController(PartnerEpisodeService episodes, PartnerVideoUploadService uploads) {
        this.episodes = episodes;
        this.uploads = uploads;
    }

    @PostMapping("/partner/v1/dramas/{drama_id}/episodes")
    public ResponseEntity<Map<String, Object>> createEpisode(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(episodes.create(partner.user(), dramaId, body));
    }

    @PatchMapping("/partner/v1/episodes/{episode_id}")
    public Map<String, Object> updateEpisode(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId,
            @RequestBody(required = false) JsonNode body) {
        return episodes.update(partner.user(), episodeId, body);
    }

    @DeleteMapping("/partner/v1/episodes/{episode_id}")
    public ResponseEntity<Void> deleteEpisode(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId) {
        episodes.delete(partner.user(), episodeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/partner/v1/episodes/{episode_id}/preview-url")
    public Map<String, Object> previewUrl(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId) {
        return episodes.previewUrl(partner.user(), episodeId);
    }

    @PostMapping("/partner/v1/episodes/{episode_id}/upload-session")
    public Map<String, Object> uploadSession(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId,
            @RequestBody(required = false) JsonNode body) {
        return uploads.createSession(partner.user(), episodeId, body);
    }

    @PostMapping("/partner/v1/episodes/{episode_id}/upload-headers")
    public Map<String, Object> uploadHeaders(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId) {
        return uploads.uploadHeaders(partner.user(), episodeId);
    }

    @GetMapping("/partner/v1/episodes/{episode_id}/upload-status")
    public Map<String, Object> uploadStatus(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId) {
        return uploads.uploadStatus(partner.user(), episodeId);
    }

    @PostMapping("/partner/v1/episodes/{episode_id}/upload-failed")
    public Map<String, Object> uploadFailed(
            @CurrentPartner PartnerContext partner,
            @PathVariable("episode_id") long episodeId,
            @RequestBody(required = false) JsonNode body) {
        return uploads.uploadFailed(partner.user(), episodeId, body);
    }
}
