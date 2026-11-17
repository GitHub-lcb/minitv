package us.minitv.admin.flow.content;

import us.minitv.common.ApiException;
import us.minitv.security.StaffContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role gates copied verbatim from {@code backend/app/modules/ops/router.py}.
 *
 * <p>{@code @CurrentStaff} already enforces the {@code staff_only} rule (a
 * partner account is rejected). The finer gates below match
 * {@code require_content_writer} and {@code require_admin_role} 1:1, including
 * the error strings.</p>
 */
final class AFContentGuards {

    /** {@code CONTENT_WRITER_ROLES = (ROLE_OPS, ROLE_ADMIN)}. */
    static final Set<String> CONTENT_WRITER_ROLES = Set.of("ops", "admin");

    private AFContentGuards() {
    }

    /** {@code require_content_writer}: 403 when the role may not write content. */
    static void requireContentWriter(StaffContext staff) {
        String role = staff.user() == null ? "" : staff.user().getRole();
        if (!CONTENT_WRITER_ROLES.contains(role)) {
            throw ApiException.of(403, "role '" + role + "' cannot write content");
        }
    }

    /** {@code require_admin_role}: super-admin only (403 detail "admin only"). */
    static void requireAdminRole(StaffContext staff) {
        String role = staff.user() == null ? "" : staff.user().getRole();
        if (!"admin".equals(role)) {
            throw ApiException.of(403, "admin only");
        }
    }

    /** FastAPI-shaped query validation error ({@code detail: [{loc, msg, type}]}). */
    static ApiException queryValidation(String name, String type, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", List.of("query", name));
        item.put("msg", message);
        return new ApiException(422, List.of(item));
    }

    /** FastAPI-shaped body validation error, used for the ScheduleIn datetime parse. */
    static ApiException bodyValidation(String name, String type, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", List.of("body", name));
        item.put("msg", message);
        return new ApiException(422, List.of(item));
    }
}
