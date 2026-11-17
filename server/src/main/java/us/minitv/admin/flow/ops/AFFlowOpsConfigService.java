package us.minitv.admin.flow.ops;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.ops.row.BannerRow;
import us.minitv.ops.row.ChannelTabRow;
import us.minitv.ops.row.CopyEntrieRow;
import us.minitv.ops.row.HotSearcheRow;
import us.minitv.ops.row.LegalPageRow;
import us.minitv.ops.row.MarketingStatRow;
import us.minitv.ops.row.StoreLinkRow;
import us.minitv.social.row.PushRecordRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Copy entries, channel tabs, banners, hot searches, marketing stats, pushes, legal pages, store links. */
@Service
public class AFFlowOpsConfigService {

    static final String DISCOVER_CODE = "discover";

    private final AFFlowOpsContentMapper content;

    public AFFlowOpsConfigService(AFFlowOpsContentMapper content) {
        this.content = content;
    }

    // ---- copies ------------------------------------------------------------

    @Transactional
    public Map<String, Object> saveCopy(AFFlowOpsBodies.CopyIn body) {
        if (body == null || body.key() == null) {
            throw ApiException.unprocessable("Field required");
        }
        String en = body.en() == null ? "" : body.en();
        String zh = body.zh() == null ? "" : body.zh();
        if (en.strip().isEmpty()) {
            throw ApiException.unprocessable("en is required");
        }
        CopyEntrieRow row = content.findCopyByKey(body.key());
        if (row == null) {
            row = new CopyEntrieRow();
            row.setKey(body.key());
            row.setEn(en);
            row.setZh(zh);
            content.insertCopy(row);
        } else {
            row.setEn(en);
            row.setZh(zh);
            content.updateCopy(row);
        }
        return AFFlowOpsSupport.map("key", row.getKey());
    }

    public Map<String, Object> listCopies() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CopyEntrieRow row : content.listCopies()) {
            items.add(AFFlowOpsSupport.map("key", row.getKey(), "en", row.getEn(), "zh", row.getZh()));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    // ---- channel tabs ------------------------------------------------------

    static Map<String, Object> tabOut(ChannelTabRow row) {
        boolean locked = Boolean.TRUE.equals(row.getLocked()) || DISCOVER_CODE.equals(row.getCode());
        return AFFlowOpsSupport.map(
                "id", row.getId(),
                "code", row.getCode(),
                "title_en", row.getTitleEn(),
                "title_zh", row.getTitleZh(),
                "position", row.getPosition(),
                "enabled", row.getEnabled(),
                "locked", locked,
                "group_id", row.getGroupId());
    }

    public Map<String, Object> listTabs() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChannelTabRow row : content.listTabs()) {
            items.add(tabOut(row));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    @Transactional
    public Map<String, Object> saveTab(AFFlowOpsBodies.TabIn body) {
        if (body == null || body.code() == null || body.title_en() == null) {
            throw ApiException.unprocessable("Field required");
        }
        if (body.code().isEmpty() || body.code().length() > 32) {
            throw ApiException.unprocessable("String should have between 1 and 32 characters");
        }
        if (body.title_en().isEmpty() || body.title_en().length() > 64) {
            throw ApiException.unprocessable("String should have between 1 and 64 characters");
        }
        String titleZh = body.title_zh() == null ? "" : body.title_zh();
        if (titleZh.length() > 64) {
            throw ApiException.unprocessable("String should have at most 64 characters");
        }
        int position = body.position() == null ? 0 : body.position();
        boolean enabled = body.enabled() == null || body.enabled();
        Long groupId = body.group_id();
        if (groupId != null && content.findGroup(groupId) == null) {
            throw ApiException.notFound("group not found");
        }
        ChannelTabRow row = content.findTabByCode(body.code());
        if (row == null) {
            row = new ChannelTabRow();
            row.setCode(body.code());
            row.setTitleEn(body.title_en());
            row.setTitleZh(titleZh);
            row.setPosition(position);
            row.setEnabled(enabled);
            boolean locked = DISCOVER_CODE.equals(body.code());
            row.setLocked(locked);
            row.setGroupId(locked ? null : groupId);
            content.insertTab(row);
        } else {
            row.setPosition(position);
            row.setTitleEn(body.title_en());
            row.setTitleZh(titleZh);
            row.setEnabled(enabled);
            boolean locked = Boolean.TRUE.equals(row.getLocked()) || DISCOVER_CODE.equals(row.getCode());
            if (!locked) {
                row.setGroupId(groupId);
            }
            content.updateTab(row);
        }
        return tabOut(row);
    }

    @Transactional
    public Map<String, Object> updateTab(long tabId, Map<String, Object> body) {
        ChannelTabRow row = content.findTab(tabId);
        if (row == null) {
            throw ApiException.notFound("tab not found");
        }
        Map<String, Object> data = body == null ? Map.of() : body;
        Long groupId = data.get("group_id") == null ? null : AFFlowOpsSupport.toInt(data.get("group_id")).longValue();
        if (groupId != null && content.findGroup(groupId) == null) {
            throw ApiException.notFound("group not found");
        }
        boolean locked = Boolean.TRUE.equals(row.getLocked()) || DISCOVER_CODE.equals(row.getCode());
        if (locked && (data.containsKey("position") || data.containsKey("enabled") || data.containsKey("group_id"))) {
            throw new ApiException(409, "discover tab is fixed");
        }
        if (data.get("title_en") != null) {
            String value = String.valueOf(data.get("title_en"));
            if (value.length() > 64) {
                throw ApiException.unprocessable("String should have at most 64 characters");
            }
            row.setTitleEn(value);
        }
        if (data.get("title_zh") != null) {
            String value = String.valueOf(data.get("title_zh"));
            if (value.length() > 64) {
                throw ApiException.unprocessable("String should have at most 64 characters");
            }
            row.setTitleZh(value);
        }
        if (data.get("position") != null) {
            row.setPosition(AFFlowOpsSupport.toInt(data.get("position")));
        }
        if (data.get("enabled") != null) {
            row.setEnabled(AFFlowOpsSupport.toBool(data.get("enabled")));
        }
        if (data.containsKey("group_id")) {
            row.setGroupId(groupId);
        }
        content.updateTab(row);
        return tabOut(row);
    }

    @Transactional
    public Map<String, Object> deleteTab(long tabId) {
        ChannelTabRow row = content.findTab(tabId);
        if (row == null) {
            throw ApiException.notFound("tab not found");
        }
        if (Boolean.TRUE.equals(row.getLocked()) || DISCOVER_CODE.equals(row.getCode())) {
            throw new ApiException(409, "discover tab is fixed");
        }
        content.deleteTab(tabId);
        return AFFlowOpsSupport.map("deleted", true);
    }

    // ---- banners -----------------------------------------------------------

    static Map<String, Object> bannerOut(BannerRow row, DramaRow drama) {
        return AFFlowOpsSupport.map(
                "id", row.getId(),
                "slot", row.getSlot(),
                "drama_id", row.getDramaId(),
                "title", drama == null ? null : drama.getTitle(),
                "cover_url", drama == null ? null : drama.getCoverUrl(),
                "kicker", row.getKicker(),
                "headline", row.getHeadline(),
                "status", row.getStatus(),
                "position", row.getPosition());
    }

    public Map<String, Object> listBanners(String slot) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BannerRow row : content.listBanners(slot)) {
            DramaRow drama = row.getDramaId() == null ? null : content.findDrama(row.getDramaId());
            items.add(bannerOut(row, drama));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    @Transactional
    public Map<String, Object> saveBanner(AFFlowOpsBodies.BannerIn body) {
        if (body == null || body.drama_id() == null) {
            throw ApiException.unprocessable("Field required");
        }
        DramaRow drama = content.findDrama(body.drama_id());
        if (drama == null) {
            throw ApiException.notFound("drama not found");
        }
        String slot = body.slot() == null ? "home" : body.slot();
        String status = body.status() == null ? "online" : body.status();
        String kicker = body.kicker() == null ? "" : body.kicker();
        String headline = body.headline() == null ? "" : body.headline();
        if (kicker.length() > 80 || headline.length() > 80) {
            throw ApiException.unprocessable("String should have at most 80 characters");
        }
        long count = content.countOnlineBanners(slot);
        if ("online".equals(status) && count >= 3) {
            throw new ApiException(409, "a banner holds 3 dramas at most");
        }
        BannerRow row = new BannerRow();
        row.setDramaId(drama.getId());
        row.setSlot(slot);
        row.setStatus(status);
        row.setPosition(body.position() == null ? 0 : body.position());
        row.setKicker(kicker);
        row.setHeadline(headline.isEmpty() ? drama.getTitle() : headline);
        content.insertBanner(row);
        return bannerOut(row, drama);
    }

    @Transactional
    public Map<String, Object> updateBanner(long bannerId, Map<String, Object> body) {
        BannerRow row = content.findBanner(bannerId);
        if (row == null) {
            throw ApiException.notFound("banner not found");
        }
        Map<String, Object> data = body == null ? Map.of() : body;
        DramaRow drama = row.getDramaId() == null ? null : content.findDrama(row.getDramaId());
        if (data.get("drama_id") != null) {
            long newDramaId = AFFlowOpsSupport.toInt(data.get("drama_id")).longValue();
            drama = content.findDrama(newDramaId);
            if (drama == null) {
                throw ApiException.notFound("drama not found");
            }
            row.setDramaId(drama.getId());
        }
        if (data.get("status") != null) {
            row.setStatus(String.valueOf(data.get("status")));
        }
        if (data.get("position") != null) {
            row.setPosition(AFFlowOpsSupport.toInt(data.get("position")));
        }
        if (data.get("kicker") != null) {
            String value = String.valueOf(data.get("kicker"));
            if (value.length() > 80) {
                throw ApiException.unprocessable("String should have at most 80 characters");
            }
            row.setKicker(value);
        }
        if (data.get("headline") != null) {
            String value = String.valueOf(data.get("headline"));
            if (value.length() > 80) {
                throw ApiException.unprocessable("String should have at most 80 characters");
            }
            row.setHeadline(value);
        }
        content.updateBanner(row);
        return bannerOut(row, drama);
    }

    @Transactional
    public Map<String, Object> deleteBanner(long bannerId) {
        BannerRow row = content.findBanner(bannerId);
        if (row == null) {
            throw ApiException.notFound("banner not found");
        }
        content.deleteBanner(bannerId);
        return AFFlowOpsSupport.map("deleted", true);
    }

    // ---- hot searches / marketing stats -----------------------------------

    @Transactional
    public Map<String, Object> saveHotSearch(String term, int position) {
        HotSearcheRow row = new HotSearcheRow();
        row.setTerm(term);
        row.setPosition(position);
        row.setEnabled(true);
        content.insertHotSearch(row);
        return AFFlowOpsSupport.map("id", row.getId());
    }

    @Transactional
    public Map<String, Object> saveMarketingStat(String label, String value, int position) {
        MarketingStatRow row = new MarketingStatRow();
        row.setLabel(label);
        row.setValue(value);
        row.setPosition(position);
        content.insertMarketingStat(row);
        return AFFlowOpsSupport.map("id", row.getId());
    }

    // ---- pushes ------------------------------------------------------------

    @Transactional
    public Map<String, Object> createPush(String title, String body, Long dramaId, long deliveredCount) {
        PushRecordRow row = new PushRecordRow();
        row.setTitle(title);
        row.setBody(body);
        row.setDramaId(dramaId);
        row.setDeliveredCount(deliveredCount);
        content.insertPush(row);
        PushRecordRow saved = content.findPush(row.getId());
        return AFFlowOpsSupport.map(
                "id", saved.getId(),
                "delivered_count", saved.getDeliveredCount(),
                "sent_at", saved.getSentAt());
    }

    public Map<String, Object> listPushes() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PushRecordRow row : content.listPushes()) {
            items.add(AFFlowOpsSupport.map(
                    "id", row.getId(),
                    "title", row.getTitle(),
                    "delivered_count", row.getDeliveredCount(),
                    "sent_at", row.getSentAt()));
        }
        return AFFlowOpsSupport.map("items", items);
    }

    // ---- legal pages / store links ----------------------------------------

    @Transactional
    public Map<String, Object> saveLegal(String slug, String title, String body) {
        LegalPageRow row = content.findLegal(slug);
        if (row == null) {
            row = new LegalPageRow();
            row.setSlug(slug);
            row.setTitle(title);
            row.setBody(body);
            content.insertLegal(row);
        } else {
            row.setTitle(title);
            row.setBody(body);
            content.updateLegal(row);
        }
        return AFFlowOpsSupport.map("slug", slug);
    }

    @Transactional
    public Map<String, Object> saveStore(String platform, String url) {
        StoreLinkRow row = content.findStoreLink(platform);
        if (row == null) {
            row = new StoreLinkRow();
            row.setPlatform(platform);
            row.setUrl(url);
            content.insertStoreLink(row);
        } else {
            row.setUrl(url);
            content.updateStoreLink(row);
        }
        return AFFlowOpsSupport.map("platform", platform, "url", url);
    }
}
