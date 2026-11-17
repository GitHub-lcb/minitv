package us.minitv.partner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.util.Map;

/** Supplier analytics and settlements (P4). */
@RestController
public class PartnerStatsController {

    private final PartnerStatsService stats;

    public PartnerStatsController(PartnerStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/partner/v1/stats/overview")
    public Map<String, Object> overview(
            @CurrentPartner PartnerContext partner,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return stats.overview(partner.user(), partner.supplier(), days);
    }

    @GetMapping("/partner/v1/stats/dramas")
    public Map<String, Object> dramas(
            @CurrentPartner PartnerContext partner,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return stats.dramas(partner.user(), partner.supplier(), days);
    }

    @GetMapping("/partner/v1/settlements")
    public Map<String, Object> settlements(@CurrentPartner PartnerContext partner) {
        return stats.settlements(partner.user());
    }
}
