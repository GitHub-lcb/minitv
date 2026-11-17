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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.util.Map;

/** Supplier drama surface: tags, listing, CRUD, cover upload, submit and delete. */
@RestController
public class PartnerDramaController {

    private final PartnerDramaService dramas;

    public PartnerDramaController(PartnerDramaService dramas) {
        this.dramas = dramas;
    }

    /** Only enabled tags; suppliers may pick from the platform taxonomy. */
    @GetMapping("/partner/v1/tags")
    public Map<String, Object> listTags(@CurrentPartner PartnerContext partner) {
        return dramas.listTags();
    }

    @GetMapping("/partner/v1/dramas")
    public Map<String, Object> listDramas(
            @CurrentPartner PartnerContext partner,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "review_state", required = false) String reviewState,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return dramas.list(partner.user(), status, reviewState, q, page, pageSize);
    }

    @PostMapping("/partner/v1/dramas")
    public ResponseEntity<Map<String, Object>> createDrama(
            @CurrentPartner PartnerContext partner,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dramas.create(partner.user(), body));
    }

    @GetMapping("/partner/v1/dramas/{drama_id}")
    public Map<String, Object> getDrama(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId) {
        return dramas.get(partner.user(), dramaId);
    }

    @PatchMapping("/partner/v1/dramas/{drama_id}")
    public Map<String, Object> updateDrama(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId,
            @RequestBody(required = false) JsonNode body) {
        return dramas.update(partner.user(), dramaId, body);
    }

    @PostMapping("/partner/v1/dramas/{drama_id}/cover")
    public Map<String, Object> uploadCover(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        return dramas.uploadCover(partner.user(), dramaId, file);
    }

    @DeleteMapping("/partner/v1/dramas/{drama_id}")
    public ResponseEntity<Void> deleteDrama(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId) {
        dramas.delete(partner.user(), dramaId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/partner/v1/dramas/{drama_id}/submit")
    public Map<String, Object> submitDrama(
            @CurrentPartner PartnerContext partner,
            @PathVariable("drama_id") long dramaId) {
        return dramas.submit(partner.user(), dramaId);
    }
}
