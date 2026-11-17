package us.minitv.pcatalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.common.ApiException;
import us.minitv.pcatalog.service.PCatalogContentService;

import java.util.Map;

/** Public content router: /v1 - the only client-facing read surface (no auth). */
@RestController
public class PCatalogPublicController {

    private final PCatalogContentService contentService;

    public PCatalogPublicController(PCatalogContentService contentService) {
        this.contentService = contentService;
    }

    /** GET /v1/preview - website trial surface, first N dramas per category. */
    @GetMapping("/v1/preview")
    public Map<String, Object> preview() {
        return contentService.preview();
    }

    /** GET /v1/preview/dramas/{drama_id} - preview detail with the published episode list. */
    @GetMapping("/v1/preview/dramas/{drama_id}")
    public Map<String, Object> previewDrama(@PathVariable("drama_id") long dramaId) {
        return contentService.previewDrama(dramaId);
    }

    /** GET /v1/home - published dramas, most recently updated first. */
    @GetMapping("/v1/home")
    public Map<String, Object> home() {
        return contentService.home();
    }

    /** GET /v1/dramas/{drama_id}. */
    @GetMapping("/v1/dramas/{drama_id}")
    public Map<String, Object> drama(@PathVariable("drama_id") long dramaId) {
        return contentService.dramaDetail(dramaId);
    }

    /** GET /v1/search?q= - title search over published dramas. */
    @GetMapping("/v1/search")
    public Map<String, Object> search(@RequestParam("q") String q) {
        if (q.isEmpty()) {
            // FastAPI/pydantic emitted this exact 422 entry for Query(min_length=1).
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("type", "string_too_short");
            item.put("loc", java.util.List.of("query", "q"));
            item.put("msg", "String should have at least 1 character");
            item.put("input", q);
            item.put("ctx", java.util.Map.of("min_length", 1));
            throw new ApiException(422, java.util.List.of(item));
        }
        return contentService.search(q);
    }
}
