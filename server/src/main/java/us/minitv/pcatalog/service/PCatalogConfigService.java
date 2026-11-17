package us.minitv.pcatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.billing.row.BillingSettingRow;
import us.minitv.billing.row.PlanRow;
import us.minitv.billing.row.PlanVersionRow;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.common.row.AuditLogRow;
import us.minitv.content.row.DramaHomeGroupRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.HomeGroupRow;
import us.minitv.ops.row.BannerRow;
import us.minitv.ops.row.ChannelTabRow;
import us.minitv.ops.row.CopyEntrieRow;
import us.minitv.ops.row.HotSearcheRow;
import us.minitv.ops.row.LegalPageRow;
import us.minitv.ops.row.MarketingStatRow;
import us.minitv.ops.row.StoreLinkRow;
import us.minitv.ops.row.TestimonialRow;
import us.minitv.pcatalog.mapper.PCatalogAuditMapper;
import us.minitv.pcatalog.mapper.PCatalogBillingMapper;
import us.minitv.pcatalog.mapper.PCatalogHomeGroupMapper;
import us.minitv.pcatalog.mapper.PCatalogOpsConfigMapper;
import us.minitv.pcatalog.support.PCatalogOut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public configuration surfaces from {@code public_flow.py}: /v1/config,
 * /v1/marketing, /v1/legal/{slug}, /v1/search/trending and /v1/home/groups.
 */
@Service
public class PCatalogConfigService {

    private static final int MARKETING_TRENDING_LIMIT = 6;

    private final PCatalogOpsConfigMapper configMapper;
    private final PCatalogHomeGroupMapper homeGroupMapper;
    private final PCatalogBillingMapper billingMapper;
    private final PCatalogAuditMapper auditMapper;
    private final PCatalogContentService contentService;

    public PCatalogConfigService(PCatalogOpsConfigMapper configMapper,
                                 PCatalogHomeGroupMapper homeGroupMapper,
                                 PCatalogBillingMapper billingMapper,
                                 PCatalogAuditMapper auditMapper,
                                 PCatalogContentService contentService) {
        this.configMapper = configMapper;
        this.homeGroupMapper = homeGroupMapper;
        this.billingMapper = billingMapper;
        this.auditMapper = auditMapper;
        this.contentService = contentService;
    }

    /** GET /v1/config?locale=&lt;en|zh&gt;. */
    @Transactional
    public Map<String, Object> config(String locale) {
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (ChannelTabRow tab : configMapper.listEnabledTabs()) {
            tabs.add(PCatalogOut.of(
                    "code", tab.getCode(),
                    "title_en", tab.getTitleEn(),
                    "title_zh", tab.getTitleZh(),
                    "position", tab.getPosition(),
                    "locked", tab.getLocked(),
                    "group_id", tab.getGroupId()));
        }

        boolean zh = "zh".equals(locale);
        Map<String, String> copies = new LinkedHashMap<>();
        for (CopyEntrieRow row : configMapper.listCopies()) {
            boolean hasZh = row.getZh() != null && !row.getZh().isEmpty();
            if (zh && hasZh) {
                copies.put(row.getKey(), row.getZh());
            } else {
                if (zh) {
                    // Missing translation: serve English and leave an audit trail (Python did
                    // this per row, then committed once at the end of the request).
                    AuditLogRow audit = new AuditLogRow();
                    audit.setActorType("system");
                    audit.setActorId(null);
                    audit.setAction("copy_fallback");
                    audit.setTargetType("copy");
                    audit.setTargetId(row.getId());
                    audit.setPayload(Json.write(PCatalogOut.of("key", row.getKey())));
                    auditMapper.insert(audit);
                }
                copies.put(row.getKey(), row.getEn());
            }
        }
        return PCatalogOut.of("tabs", tabs, "copies", copies);
    }

    /** GET /v1/marketing. */
    @Transactional
    public Map<String, Object> marketing() {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (MarketingStatRow row : configMapper.listMarketingStats()) {
            stats.add(PCatalogOut.of("label", row.getLabel(), "value", row.getValue()));
        }
        List<Map<String, Object>> testimonials = new ArrayList<>();
        for (TestimonialRow row : configMapper.listTestimonials()) {
            testimonials.add(PCatalogOut.of("author", row.getAuthor(), "body", row.getBody()));
        }
        Map<String, String> stores = new LinkedHashMap<>();
        for (StoreLinkRow row : configMapper.listStoreLinks()) {
            stores.put(row.getPlatform(), row.getUrl());
        }

        List<DramaRow> published = contentService.listPublishedDramas();
        List<DramaRow> trendingSource = published.size() > MARKETING_TRENDING_LIMIT
                ? published.subList(0, MARKETING_TRENDING_LIMIT) : published;
        List<Map<String, Object>> trending = new ArrayList<>();
        for (DramaRow drama : trendingSource) {
            trending.add(PCatalogOut.of(
                    "id", drama.getId(),
                    "title", drama.getTitle(),
                    "cover_url", drama.getCoverUrl(),
                    "play_count", drama.getPlayCount()));
        }

        return PCatalogOut.of(
                "stats", stats,
                "testimonials", testimonials,
                "stores", stores,
                "trending", trending,
                "plans", currentPlans());
    }

    /** GET /v1/legal/{slug}. */
    public Map<String, Object> legal(String slug) {
        LegalPageRow page = configMapper.findLegalPage(slug);
        if (page == null) {
            throw ApiException.notFound("not found");
        }
        return PCatalogOut.of("slug", page.getSlug(), "title", page.getTitle(), "body", page.getBody());
    }

    /** GET /v1/search/trending. */
    public Map<String, Object> trending() {
        List<String> items = new ArrayList<>();
        for (HotSearcheRow row : configMapper.listEnabledHotSearches()) {
            items.add(row.getTerm());
        }
        return PCatalogOut.of("items", items);
    }

    /** GET /v1/home/groups. */
    public Map<String, Object> homeGroups() {
        List<HomeGroupRow> groups = homeGroupMapper.listActiveGroups();
        List<Long> groupIds = new ArrayList<>();
        for (HomeGroupRow group : groups) {
            groupIds.add(group.getId());
        }
        Map<Long, List<DramaHomeGroupRow>> linksByGroup = new LinkedHashMap<>();
        if (!groupIds.isEmpty()) {
            for (DramaHomeGroupRow link : homeGroupMapper.linksByGroupIds(groupIds)) {
                linksByGroup.computeIfAbsent(link.getGroupId(), key -> new ArrayList<>()).add(link);
            }
        }

        List<BannerRow> banners = homeGroupMapper.listOnlineBanners();

        Set<Long> dramaIds = new LinkedHashSet<>();
        for (List<DramaHomeGroupRow> links : linksByGroup.values()) {
            for (DramaHomeGroupRow link : links) {
                dramaIds.add(link.getDramaId());
            }
        }
        for (BannerRow banner : banners) {
            if (banner.getDramaId() != null) {
                dramaIds.add(banner.getDramaId());
            }
        }
        Map<Long, DramaRow> published = new LinkedHashMap<>();
        for (DramaRow drama : contentService.publishedDramasByIds(dramaIds)) {
            published.put(drama.getId(), drama);
        }

        List<Map<String, Object>> groupPayload = new ArrayList<>();
        for (HomeGroupRow group : groups) {
            List<Map<String, Object>> dramas = new ArrayList<>();
            for (DramaHomeGroupRow link : linksByGroup.getOrDefault(group.getId(), List.of())) {
                DramaRow drama = published.get(link.getDramaId());
                if (drama == null) {
                    continue;
                }
                dramas.add(PCatalogOut.of(
                        "id", drama.getId(),
                        "title", drama.getTitle(),
                        "cover_url", drama.getCoverUrl(),
                        "position", link.getPositionInGroup(),
                        "play_count", drama.getPlayCount()));
            }
            groupPayload.add(PCatalogOut.of(
                    "id", group.getId(),
                    "name", group.getName(),
                    "position", group.getPosition(),
                    "style", group.getStyle(),
                    "badge", group.getBadge(),
                    "dramas", dramas));
        }

        List<Map<String, Object>> bannerPayload = new ArrayList<>();
        for (BannerRow banner : banners) {
            DramaRow drama = banner.getDramaId() == null ? null : published.get(banner.getDramaId());
            String headline = banner.getHeadline() == null || banner.getHeadline().isEmpty()
                    ? (drama == null ? "" : drama.getTitle())
                    : banner.getHeadline();
            bannerPayload.add(PCatalogOut.of(
                    "id", banner.getId(),
                    "drama_id", banner.getDramaId(),
                    "slot", banner.getSlot(),
                    "title", drama == null ? null : drama.getTitle(),
                    "cover_url", drama == null ? null : drama.getCoverUrl(),
                    "kicker", banner.getKicker(),
                    "headline", headline));
        }
        return PCatalogOut.of("groups", groupPayload, "banners", bannerPayload);
    }

    // ------------------------------------------------------------------ helpers

    /** billing.list_current_plans: active plans with their newest version. */
    private List<Map<String, Object>> currentPlans() {
        String mode = billingMode();
        List<Map<String, Object>> plans = new ArrayList<>();
        for (PlanRow plan : billingMapper.listActivePlans()) {
            PlanVersionRow version = billingMapper.latestVersion(plan.getId());
            if (version == null) {
                continue;
            }
            JsonNode benefits = Json.read(version.getBenefits());
            plans.add(PCatalogOut.of(
                    "code", plan.getCode(),
                    "name_en", plan.getNameEn(),
                    "name_zh", plan.getNameZh(),
                    "price_cents", version.getPriceCents(),
                    "currency", version.getCurrency(),
                    "version", version.getVersion(),
                    "version_id", version.getId(),
                    "benefits", benefits,
                    "billing_mode", mode));
        }
        return plans;
    }

    /** billing.billing_mode: get_billing_settings creates the singleton row when missing. */
    private String billingMode() {
        BillingSettingRow row = billingMapper.findSettings(1);
        if (row == null) {
            billingMapper.insertSettings(1, "one_time");
            return "one_time";
        }
        return row.getBillingMode();
    }
}
