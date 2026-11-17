package us.minitv.security;

import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;

/** Resolved back-office principal: a staff session or an API key. */
public record StaffContext(AdminUserRow user, AdminApiKeyRow key, String token) {

    public boolean viaApiKey() {
        return key != null;
    }
}
