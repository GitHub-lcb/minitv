package us.minitv.security;

import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;

/** Resolved supplier (上剧方) principal plus its tenant row. */
public record PartnerContext(AdminUserRow user, AdminApiKeyRow key, String token, SupplierRow supplier) {

    public boolean viaApiKey() {
        return key != null;
    }

    public long supplierId() {
        return supplier.getId();
    }
}
