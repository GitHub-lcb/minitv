package us.minitv.pcatalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.pcatalog.service.PCatalogConfigService;

import java.util.Map;

/** Public configuration surfaces: config, marketing, legal, trending, home groups. */
@RestController
public class PCatalogFlowController {

    private final PCatalogConfigService configService;

    public PCatalogFlowController(PCatalogConfigService configService) {
        this.configService = configService;
    }

    /** GET /v1/config?locale=en - channel tabs plus UI copy. */
    @GetMapping("/v1/config")
    public Map<String, Object> config(@RequestParam(value = "locale", defaultValue = "en") String locale) {
        return configService.config(locale);
    }

    /** GET /v1/marketing - configured marketing numbers, testimonials, stores and plans. */
    @GetMapping("/v1/marketing")
    public Map<String, Object> marketing() {
        return configService.marketing();
    }

    /** GET /v1/legal/{slug}. */
    @GetMapping("/v1/legal/{slug}")
    public Map<String, Object> legal(@PathVariable("slug") String slug) {
        return configService.legal(slug);
    }

    /** GET /v1/search/trending - configured hot search terms. */
    @GetMapping("/v1/search/trending")
    public Map<String, Object> trending() {
        return configService.trending();
    }

    /** GET /v1/home/groups - homepage floors plus banners. */
    @GetMapping("/v1/home/groups")
    public Map<String, Object> homeGroups() {
        return configService.homeGroups();
    }
}
