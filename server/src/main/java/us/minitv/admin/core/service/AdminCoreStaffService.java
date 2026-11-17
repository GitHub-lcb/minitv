package us.minitv.admin.core.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.mapper.AdminCoreAdminUserMapper;
import us.minitv.admin.core.mapper.AdminCoreSupplierMapper;
import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;
import us.minitv.security.PasswordHasher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Staff account management (ops/router.py "staff accounts" section). */
@Service
public class AdminCoreStaffService {

    public static final String ROLE_OPS = "ops";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_PARTNER = "partner";

    private final AdminCoreAdminUserMapper users;
    private final AdminCoreSupplierMapper suppliers;
    private final AdminCoreSessionService sessions;
    private final AdminCoreAuditService audit;

    public AdminCoreStaffService(AdminCoreAdminUserMapper users,
                                 AdminCoreSupplierMapper suppliers,
                                 AdminCoreSessionService sessions,
                                 AdminCoreAuditService audit) {
        this.users = users;
        this.suppliers = suppliers;
        this.sessions = sessions;
        this.audit = audit;
    }

    public Map<String, Object> list(String q, String role, String status) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminUserRow row : users.list(AdminCoreSupport.needle(q), emptyToNull(role), emptyToNull(status))) {
            items.add(staffOut(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    @Transactional
    public Map<String, Object> create(AdminUserRow actor, String username, String displayName, String role,
                                      String password, Long supplierId) {
        String cleanUsername = AdminCoreSupport.cleanUsername(username);
        String roleValue = role == null ? ROLE_OPS : role;
        AdminCoreSupport.validateRole(roleValue);
        String displayNameRaw = displayName == null ? "" : displayName;
        if (displayNameRaw.length() > 64) {
            throw ApiException.unprocessable("display_name: String should have at most 64 characters");
        }
        AdminCoreSupport.stringLength("password", password, 8, 128);
        AdminCoreSupport.validatePassword(password, cleanUsername);
        if (ROLE_PARTNER.equals(roleValue)) {
            if (supplierId == null) {
                throw ApiException.unprocessable("上剧方账号必须绑定供应商");
            }
            SupplierRow supplier = suppliers.findById(supplierId);
            if (supplier == null) {
                throw ApiException.notFound("supplier not found");
            }
            if (users.findBySupplierId(supplierId) != null) {
                throw ApiException.conflict("该供应商已有账号");
            }
        } else if (supplierId != null) {
            throw ApiException.unprocessable("只有上剧方账号可以绑定供应商");
        }
        if (users.countByUsername(cleanUsername) > 0) {
            throw ApiException.conflict("账号已存在");
        }
        AdminUserRow row = new AdminUserRow();
        row.setUsername(cleanUsername);
        row.setDisplayName(displayNameRaw.trim());
        row.setRole(roleValue);
        row.setPasswordHash(PasswordHasher.hash(password));
        row.setStatus("active");
        row.setSupplierId(supplierId);
        try {
            users.insert(row);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("账号已存在");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", row.getUsername());
        payload.put("role", row.getRole());
        audit.record(actor.getId(), "staff_create", "admin_user", row.getId(), payload);
        return staffOut(users.findById(row.getId()));
    }

    @Transactional
    public Map<String, Object> update(AdminUserRow actor, long staffId, Map<String, Object> body) {
        AdminUserRow row = users.findById(staffId);
        if (row == null) {
            throw ApiException.notFound("staff not found");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        String newDisplayName = null;
        boolean setDisplayName = false;
        String newRole = null;
        boolean setRole = false;

        if (AdminCoreSupport.hasValue(body, "display_name")) {
            String raw = AdminCoreSupport.bodyString(body, "display_name", 0, 64);
            String displayName = raw.trim();
            if (!displayName.equals(row.getDisplayName())) {
                before.put("display_name", row.getDisplayName());
                newDisplayName = displayName;
                setDisplayName = true;
                changed.add("display_name");
            }
        }

        if (AdminCoreSupport.hasValue(body, "role")) {
            String rawRole = AdminCoreSupport.bodyString(body, "role", 0, 0);
            if (!rawRole.equals(row.getRole())) {
                AdminCoreSupport.validateRole(rawRole);
                if (row.getId() == actor.getId()) {
                    throw ApiException.badRequest("不能修改自己的角色");
                }
                // Supplier accounts are created together with their tenant; moving a
                // staff account into (or out of) a supplier would orphan its dramas.
                if (ROLE_PARTNER.equals(row.getRole()) || ROLE_PARTNER.equals(rawRole)) {
                    throw ApiException.badRequest("上剧方账号的角色不可修改，请改绑供应商");
                }
                if (ROLE_ADMIN.equals(row.getRole()) && "active".equals(row.getStatus())) {
                    ensureAdminRemains(row);
                }
                before.put("role", row.getRole());
                newRole = rawRole;
                setRole = true;
                changed.add("role");
            }
        }

        if (changed.isEmpty()) {
            return updateOut(new LinkedHashMap<>(), staffOut(row));
        }

        users.updateProfile(row.getId(), setDisplayName, newDisplayName, setRole, newRole, Times.now());
        Map<String, Object> to = new LinkedHashMap<>();
        for (String key : changed) {
            if ("display_name".equals(key)) {
                to.put("display_name", newDisplayName);
            } else {
                to.put("role", newRole);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changed", changed);
        payload.put("from", before);
        payload.put("to", to);
        audit.record(actor.getId(), "staff_update", "admin_user", row.getId(), payload);
        return updateOut(before, staffOut(users.findById(row.getId())));
    }

    @Transactional
    public Map<String, Object> setStatus(AdminUserRow actor, long staffId, String status, String reason) {
        AdminCoreSupport.validateStatus(status);
        AdminUserRow row = users.findById(staffId);
        if (row == null) {
            throw ApiException.notFound("staff not found");
        }
        if (status.equals(row.getStatus())) {
            return statusOut(row.getId(), row.getStatus(), row.getDisableReason(), 0);
        }
        String cleanReason = AdminCoreSupport.stripToNull(reason);
        int killed = 0;
        if ("disabled".equals(status)) {
            if (row.getId() == actor.getId()) {
                throw ApiException.badRequest("不能禁用自己");
            }
            if (ROLE_ADMIN.equals(row.getRole()) && "active".equals(row.getStatus())) {
                ensureAdminRemains(row);
            }
            // Revoke before commit: a Redis outage must leave the account usable.
            killed = sessions.revokeSessions(row.getId());
        }
        users.updateStatus(row.getId(), status, "disabled".equals(status) ? cleanReason : null, Times.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", cleanReason);
        payload.put("revoked_sessions", killed);
        audit.record(actor.getId(), "disabled".equals(status) ? "staff_disable" : "staff_enable",
                "admin_user", row.getId(), payload);
        AdminUserRow fresh = users.findById(row.getId());
        return statusOut(fresh.getId(), fresh.getStatus(), fresh.getDisableReason(), killed);
    }

    @Transactional
    public void resetPassword(AdminUserRow actor, long staffId, String newPassword) {
        AdminUserRow row = users.findById(staffId);
        if (row == null) {
            throw ApiException.notFound("staff not found");
        }
        AdminCoreSupport.stringLength("new_password", newPassword, 8, 128);
        AdminCoreSupport.validatePassword(newPassword, row.getUsername());
        int killed = sessions.revokeSessions(row.getId());
        users.updatePassword(row.getId(), PasswordHasher.hash(newPassword), Times.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revoked_sessions", killed);
        audit.record(actor.getId(), "staff_password_reset", "admin_user", row.getId(), payload);
    }

    private void ensureAdminRemains(AdminUserRow row) {
        if (users.countOtherActiveAdmins(row.getId()) == 0) {
            throw ApiException.conflict("至少要保留一个启用状态的管理员");
        }
    }

    private static Map<String, Object> updateOut(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", before);
        out.put("after", after);
        return out;
    }

    private static Map<String, Object> statusOut(long id, String status, String disableReason, int revokedSessions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("disable_reason", disableReason);
        out.put("revoked_sessions", revokedSessions);
        return out;
    }

    /** Never serialise password_hash - not even a prefix. */
    public static Map<String, Object> staffOut(AdminUserRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("username", row.getUsername());
        out.put("display_name", row.getDisplayName());
        out.put("role", row.getRole());
        out.put("status", row.getStatus());
        out.put("disable_reason", row.getDisableReason());
        out.put("supplier_id", row.getSupplierId());
        out.put("last_login_at", row.getLastLoginAt());
        out.put("created_at", row.getCreatedAt());
        out.put("updated_at", row.getUpdatedAt());
        return out;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
