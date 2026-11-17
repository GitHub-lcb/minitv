package us.minitv.admin.core.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreAdminUserMapper;
import us.minitv.admin.core.mapper.AdminCoreSupplierMapper;
import us.minitv.admin.core.mapper.AdminCoreSupplierShareMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;
import us.minitv.ops.row.SupplierShareHistoryRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Supplier (上剧方) management: accounts stay one-per-tenant, share is effective-dated. */
@Service
public class AdminCoreSupplierService {

    private final AdminCoreSupplierMapper suppliers;
    private final AdminCoreAdminUserMapper users;
    private final AdminCoreSupplierShareMapper shares;
    private final AdminCoreSessionService sessions;
    private final AdminCoreAuditService audit;

    public AdminCoreSupplierService(AdminCoreSupplierMapper suppliers,
                                    AdminCoreAdminUserMapper users,
                                    AdminCoreSupplierShareMapper shares,
                                    AdminCoreSessionService sessions,
                                    AdminCoreAuditService audit) {
        this.suppliers = suppliers;
        this.users = users;
        this.shares = shares;
        this.sessions = sessions;
        this.audit = audit;
    }

    public Map<String, Object> list(String q, String status) {
        String needle = AdminCoreSupport.needle(q);
        String statusFilter = status == null || status.isEmpty() ? null : status;
        List<Map<String, Object>> items = new ArrayList<>();
        for (SupplierRow row : suppliers.list(needle, statusFilter)) {
            items.add(supplierOut(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    @Transactional
    public Map<String, Object> create(AdminUserRow actor, String name, String contactName, String contactEmail,
                                      Integer revenueShareBps, String note) {
        AdminCoreSupport.stringLength("name", name, 1, 100);
        String cleanName = name.trim();
        String cleanContactName = contactName == null ? "" : contactName;
        String cleanContactEmail = contactEmail == null ? "" : contactEmail;
        String cleanNote = note == null ? "" : note;
        if (cleanContactName.length() > 64) {
            throw ApiException.unprocessable("contact_name: String should have at most 64 characters");
        }
        if (cleanContactEmail.length() > 320) {
            throw ApiException.unprocessable("contact_email: String should have at most 320 characters");
        }
        if (cleanNote.length() > 500) {
            throw ApiException.unprocessable("note: String should have at most 500 characters");
        }
        int shareBps = revenueShareBps == null ? 8000 : revenueShareBps;
        if (shareBps < 0 || shareBps > 10000) {
            throw ApiException.unprocessable("revenue_share_bps: Input should be between 0 and 10000");
        }
        if (suppliers.countByName(cleanName) > 0) {
            throw ApiException.conflict("供应商名称已存在");
        }
        SupplierRow row = new SupplierRow();
        row.setName(cleanName);
        row.setContactName(cleanContactName.trim());
        row.setContactEmail(cleanContactEmail.trim());
        row.setStatus("active");
        row.setRevenueShareBps(shareBps);
        row.setNote(cleanNote.trim());
        try {
            suppliers.insert(row);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("供应商名称已存在");
        }
        recordShare(row, shareBps, actor.getId(), "初始比例");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", row.getName());
        payload.put("share_bps", row.getRevenueShareBps());
        audit.record(actor.getId(), "supplier_create", "supplier", row.getId(), payload);
        return supplierOut(suppliers.findById(row.getId()));
    }

    @Transactional
    public Map<String, Object> update(AdminUserRow actor, long supplierId, Map<String, Object> body) {
        SupplierRow row = suppliers.findById(supplierId);
        if (row == null) {
            throw ApiException.notFound("supplier not found");
        }
        // Validate every supplied field first (Pydantic validates the whole body).
        String name = AdminCoreSupport.bodyString(body, "name", 1, 100);
        String contactName = AdminCoreSupport.bodyString(body, "contact_name", 0, 64);
        String contactEmail = AdminCoreSupport.bodyString(body, "contact_email", 0, 320);
        String note = AdminCoreSupport.bodyString(body, "note", 0, 500);

        List<String> changed = new ArrayList<>();
        String newName = name == null ? null : name.trim();
        String newContactName = contactName == null ? null : contactName.trim();
        String newContactEmail = contactEmail == null ? null : contactEmail.trim();
        String newNote = note == null ? null : note.trim();
        boolean setName = newName != null && !newName.equals(row.getName());
        boolean setContactName = newContactName != null && !newContactName.equals(row.getContactName());
        boolean setContactEmail = newContactEmail != null && !newContactEmail.equals(row.getContactEmail());
        boolean setNote = newNote != null && !newNote.equals(row.getNote());
        if (setName) {
            changed.add("name");
        }
        if (setContactName) {
            changed.add("contact_name");
        }
        if (setContactEmail) {
            changed.add("contact_email");
        }
        if (setNote) {
            changed.add("note");
        }
        if (!changed.isEmpty()) {
            try {
                suppliers.updateFields(row.getId(), setName, newName, setContactName, newContactName,
                        setContactEmail, newContactEmail, setNote, newNote, Times.now());
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("供应商名称已存在");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("changed", changed);
            audit.record(actor.getId(), "supplier_update", "supplier", row.getId(), payload);
        }
        return supplierOut(suppliers.findById(row.getId()));
    }

    @Transactional
    public Map<String, Object> setStatus(AdminUserRow actor, long supplierId, String status, String reason) {
        AdminCoreSupport.validateStatus(status);
        SupplierRow row = suppliers.findById(supplierId);
        if (row == null) {
            throw ApiException.notFound("supplier not found");
        }
        String cleanReason = AdminCoreSupport.stripToNull(reason);
        AdminUserRow account = users.findBySupplierId(row.getId());
        int killed = 0;
        if ("disabled".equals(status) && account != null) {
            String disableReason = cleanReason != null ? cleanReason : "供应商停用";
            users.updateStatus(account.getId(), "disabled", disableReason, Times.now());
            account.setStatus("disabled");
            account.setDisableReason(disableReason);
            // Disabling the tenant must also cut off its login immediately.
            killed = sessions.revokeSessions(account.getId());
        } else if ("active".equals(status) && account != null) {
            users.updateStatus(account.getId(), "active", null, Times.now());
            account.setStatus("active");
            account.setDisableReason(null);
        }
        suppliers.updateStatus(row.getId(), status, Times.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", cleanReason);
        payload.put("revoked_sessions", killed);
        audit.record(actor.getId(), "disabled".equals(status) ? "supplier_disable" : "supplier_enable",
                "supplier", row.getId(), payload);
        SupplierRow fresh = suppliers.findById(row.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fresh.getId());
        out.put("status", fresh.getStatus());
        out.put("account_status", account == null ? null : account.getStatus());
        out.put("revoked_sessions", killed);
        return out;
    }

    @Transactional
    public Map<String, Object> setShare(AdminUserRow actor, long supplierId, Integer shareBps, String note) {
        if (shareBps == null) {
            throw ApiException.unprocessable("share_bps: Field required");
        }
        if (shareBps < 0 || shareBps > 10000) {
            throw ApiException.unprocessable("share_bps: Input should be between 0 and 10000");
        }
        String rawNote = note == null ? "" : note;
        if (rawNote.length() > 200) {
            throw ApiException.unprocessable("note: String should have at most 200 characters");
        }
        SupplierRow row = suppliers.findById(supplierId);
        if (row == null) {
            throw ApiException.notFound("supplier not found");
        }
        int before = row.getRevenueShareBps();
        if (before != shareBps) {
            recordShare(row, shareBps, actor.getId(), rawNote.trim());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", before);
            payload.put("to", shareBps);
            payload.put("note", rawNote);
            audit.record(actor.getId(), "supplier_share_change", "supplier", row.getId(), payload);
        }
        SupplierRow fresh = suppliers.findById(row.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fresh.getId());
        out.put("revenue_share_bps", fresh.getRevenueShareBps());
        return out;
    }

    public Map<String, Object> getShare(long supplierId) {
        SupplierRow row = suppliers.findById(supplierId);
        if (row == null) {
            throw ApiException.notFound("supplier not found");
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (SupplierShareHistoryRow item : shares.listHistory(row.getId(), 50)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("share_bps", item.getShareBps());
            entry.put("effective_from", item.getEffectiveFrom());
            entry.put("effective_to", item.getEffectiveTo());
            entry.put("changed_by", item.getChangedBy());
            entry.put("note", item.getNote());
            history.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplier_id", row.getId());
        out.put("current_bps", row.getRevenueShareBps());
        out.put("history", history);
        return out;
    }

    /** Close the open history row and open a new one (effective now). */
    private void recordShare(SupplierRow supplier, int shareBps, Long actorId, String note) {
        LocalDateTime now = Times.now();
        shares.closeOpen(supplier.getId(), now);
        SupplierShareHistoryRow history = new SupplierShareHistoryRow();
        history.setSupplierId(supplier.getId());
        history.setShareBps(shareBps);
        history.setEffectiveFrom(now);
        history.setEffectiveTo(null);
        history.setChangedBy(actorId);
        history.setNote(note == null ? "" : note);
        shares.insert(history);
        suppliers.updateShareBps(supplier.getId(), shareBps, now);
        supplier.setRevenueShareBps(shareBps);
    }

    public Map<String, Object> supplierOut(SupplierRow row) {
        long dramaCount = suppliers.countDramas(row.getId());
        AdminUserRow account = users.findBySupplierId(row.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("name", row.getName());
        out.put("contact_name", row.getContactName());
        out.put("contact_email", row.getContactEmail());
        out.put("status", row.getStatus());
        out.put("revenue_share_bps", row.getRevenueShareBps());
        out.put("note", row.getNote());
        out.put("drama_count", dramaCount);
        out.put("account", accountOut(account));
        out.put("created_at", row.getCreatedAt());
        return out;
    }

    private static Map<String, Object> accountOut(AdminUserRow account) {
        if (account == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", account.getId());
        out.put("username", account.getUsername());
        out.put("status", account.getStatus());
        out.put("last_login_at", account.getLastLoginAt());
        return out;
    }
}
