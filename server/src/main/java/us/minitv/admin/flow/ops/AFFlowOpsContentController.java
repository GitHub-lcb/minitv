package us.minitv.admin.flow.ops;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.Map;

/** Homepage copy, tabs, banners, hot searches, marketing stats, pushes, legal and stores. */
@RestController
public class AFFlowOpsContentController {

    private final AFFlowOpsConfigService config;

    public AFFlowOpsContentController(AFFlowOpsConfigService config) {
        this.config = config;
    }

    // ---- copies ------------------------------------------------------------

    @PostMapping("/admin/v1/copies")
    public Map<String, Object> saveCopy(@CurrentStaff StaffContext staff,
                                        @RequestBody AFFlowOpsBodies.CopyIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveCopy(body);
    }

    @GetMapping("/admin/v1/copies")
    public Map<String, Object> listCopies(@CurrentStaff StaffContext staff) {
        return config.listCopies();
    }

    // ---- channel tabs ------------------------------------------------------

    @GetMapping("/admin/v1/tabs")
    public Map<String, Object> listTabs(@CurrentStaff StaffContext staff) {
        return config.listTabs();
    }

    @PostMapping("/admin/v1/tabs")
    public Map<String, Object> saveTab(@CurrentStaff StaffContext staff,
                                       @RequestBody AFFlowOpsBodies.TabIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveTab(body);
    }

    @PatchMapping("/admin/v1/tabs/{tab_id}")
    public Map<String, Object> updateTab(@CurrentStaff StaffContext staff,
                                         @PathVariable("tab_id") long tabId,
                                         @RequestBody Map<String, Object> body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.updateTab(tabId, body);
    }

    @DeleteMapping("/admin/v1/tabs/{tab_id}")
    public Map<String, Object> deleteTab(@CurrentStaff StaffContext staff,
                                         @PathVariable("tab_id") long tabId) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.deleteTab(tabId);
    }

    // ---- banners -----------------------------------------------------------

    @GetMapping("/admin/v1/banners")
    public Map<String, Object> listBanners(@CurrentStaff StaffContext staff,
                                           @RequestParam(value = "slot", defaultValue = "home") String slot) {
        return config.listBanners(slot);
    }

    @PostMapping("/admin/v1/banners")
    public Map<String, Object> saveBanner(@CurrentStaff StaffContext staff,
                                          @RequestBody AFFlowOpsBodies.BannerIn body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveBanner(body);
    }

    @PatchMapping("/admin/v1/banners/{banner_id}")
    public Map<String, Object> updateBanner(@CurrentStaff StaffContext staff,
                                            @PathVariable("banner_id") long bannerId,
                                            @RequestBody Map<String, Object> body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.updateBanner(bannerId, body);
    }

    @DeleteMapping("/admin/v1/banners/{banner_id}")
    public Map<String, Object> deleteBanner(@CurrentStaff StaffContext staff,
                                            @PathVariable("banner_id") long bannerId) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.deleteBanner(bannerId);
    }

    // ---- hot searches / marketing stats -----------------------------------

    @PostMapping("/admin/v1/hot-searches")
    public Map<String, Object> saveHotSearch(@CurrentStaff StaffContext staff,
                                             @RequestParam("term") String term,
                                             @RequestParam(value = "position", defaultValue = "0") int position) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveHotSearch(term, position);
    }

    @PostMapping("/admin/v1/marketing/stats")
    public Map<String, Object> saveMarketingStat(@CurrentStaff StaffContext staff,
                                                 @RequestParam("label") String label,
                                                 @RequestParam("value") String value,
                                                 @RequestParam(value = "position", defaultValue = "0") int position) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveMarketingStat(label, value, position);
    }

    // ---- pushes ------------------------------------------------------------

    @PostMapping("/admin/v1/pushes")
    public Map<String, Object> createPush(@CurrentStaff StaffContext staff,
                                          @RequestParam("title") String title,
                                          @RequestParam(value = "body", defaultValue = "") String body,
                                          @RequestParam(value = "drama_id", required = false) Long dramaId,
                                          @RequestParam(value = "delivered_count", defaultValue = "0") long deliveredCount) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.createPush(title, body, dramaId, deliveredCount);
    }

    @GetMapping("/admin/v1/pushes")
    public Map<String, Object> listPushes(@CurrentStaff StaffContext staff) {
        return config.listPushes();
    }

    // ---- legal pages / store links ----------------------------------------

    @PostMapping("/admin/v1/legal/{slug}")
    public Map<String, Object> saveLegal(@CurrentStaff StaffContext staff,
                                         @PathVariable("slug") String slug,
                                         @RequestParam("title") String title,
                                         @RequestParam(value = "body", defaultValue = "") String body) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveLegal(slug, title, body);
    }

    @PostMapping("/admin/v1/stores")
    public Map<String, Object> saveStore(@CurrentStaff StaffContext staff,
                                         @RequestParam("platform") String platform,
                                         @RequestParam("url") String url) {
        AFFlowOpsSupport.requireContentWriter(staff);
        return config.saveStore(platform, url);
    }
}
