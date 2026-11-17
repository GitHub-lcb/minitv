package us.minitv.admin.core;

import us.minitv.common.ApiException;
import us.minitv.common.Times;
import us.minitv.ops.row.AdminUserRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Role gates and request validation shared by the admin-core controllers.
 *
 * FastAPI raised HTTPException for the checks in app/modules/ops/router.py; the
 * same status codes and Chinese messages are reproduced here. Pydantic-level
 * constraints (Field(min_length=...)) surface as 422 with the same numbers, but
 * only the message text is approximated.
 */
public final class AdminCoreSupport {

    public static final List<String> ROLES = List.of("ops", "finance", "admin", "partner");
    public static final List<String> STAFF_ROLES = List.of("ops", "finance", "admin");
    public static final List<String> STATUSES = List.of("active", "disabled");
    public static final Set<String> CONTENT_WRITER_ROLES = Set.of("ops", "admin");
    public static final Set<String> REVIEW_WRITER_ROLES = Set.of("ops", "admin");
    public static final Set<String> MONEY_ROLES = Set.of("finance", "admin");

    /** ^[a-z0-9][a-z0-9._-]{2,31}$ */
    private static final Pattern USERNAME_RE = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");
    /** ^\\d{4}-\\d{2}$ */
    private static final Pattern MONTH_RE = Pattern.compile("^\\d{4}-\\d{2}$");

    public static final String SESSION_STORE_503_DETAIL =
            "会话存储不可用（Redis 未连接）。请先启动 minitv-redis："
                    + "在仓库根目录执行 docker compose up -d，然后重试。";

    private AdminCoreSupport() {
    }

    // ---- role gates (Python: require_admin_role / require_content_writer / ...) ----

    public static AdminUserRow requireAdminRole(AdminUserRow user) {
        if (!"admin".equals(user.getRole())) {
            throw ApiException.of(403, "admin only");
        }
        return user;
    }

    public static AdminUserRow requireContentWriter(AdminUserRow user) {
        if (!CONTENT_WRITER_ROLES.contains(user.getRole())) {
            throw ApiException.of(403, "role '" + user.getRole() + "' cannot write content");
        }
        return user;
    }

    public static AdminUserRow requireReviewer(AdminUserRow user) {
        if (!REVIEW_WRITER_ROLES.contains(user.getRole())) {
            throw ApiException.of(403, "role cannot review content");
        }
        return user;
    }

    public static AdminUserRow requireMoney(AdminUserRow user) {
        if (!MONEY_ROLES.contains(user.getRole())) {
            throw ApiException.of(403, "role cannot access settlements");
        }
        return user;
    }

    // ---- validation helpers (Pydantic constraints) ----

    public static void required(String field, Object value) {
        if (value == null) {
            throw ApiException.unprocessable(field + ": Field required");
        }
    }

    public static void stringLength(String field, String value, int min, int max) {
        if (value == null) {
            throw ApiException.unprocessable(field + ": Field required");
        }
        if (value.length() < min) {
            throw ApiException.unprocessable(field + ": String should have at least " + min + " characters");
        }
        if (max > 0 && value.length() > max) {
            throw ApiException.unprocessable(field + ": String should have at most " + max + " characters");
        }
    }

    public static void range(String field, Integer value, int min, Integer max) {
        if (value == null) {
            throw ApiException.unprocessable(field + ": Field required");
        }
        if (value < min) {
            throw ApiException.unprocessable(field + ": Input should be greater than or equal to " + min);
        }
        if (max != null && value > max) {
            throw ApiException.unprocessable(field + ": Input should be less than or equal to " + max);
        }
    }

    public static void paging(int page, int pageSize) {
        if (page < 1) {
            throw ApiException.unprocessable("page: Input should be greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw ApiException.unprocessable("page_size: Input should be between 1 and 100");
        }
    }

    /** Python's ops.router._clean_username. */
    public static String cleanUsername(String raw) {
        required("username", raw);
        String username = raw.trim().toLowerCase();
        if (!USERNAME_RE.matcher(username).matches()) {
            throw ApiException.unprocessable("账号需为 3–32 位小写字母、数字或 . _ -，并以字母或数字开头");
        }
        return username;
    }

    /** Python's ops.router._validate_role. */
    public static String validateRole(String role) {
        required("role", role);
        if (!ROLES.contains(role)) {
            throw ApiException.unprocessable("role must be one of " + String.join(", ", ROLES));
        }
        return role;
    }

    /** Python's ops.router._validate_password. */
    public static String validatePassword(String password, String username) {
        required("password", password);
        if (password.length() < 8) {
            throw ApiException.unprocessable("密码至少 8 位");
        }
        if (password.length() > 128) {
            throw ApiException.unprocessable("密码最多 128 位");
        }
        if (!password.equals(password.trim())) {
            throw ApiException.unprocessable("密码首尾不能是空格");
        }
        if (password.equalsIgnoreCase(username)) {
            throw ApiException.unprocessable("密码不能与账号相同");
        }
        return password;
    }

    public static void validateStatus(String status) {
        required("status", status);
        if (!STATUSES.contains(status)) {
            throw ApiException.unprocessable("status must be one of " + String.join(", ", STATUSES));
        }
    }

    /** Python's ops.settlements.month_bounds: returns [start, end) as LocalDate. */
    public static LocalDate[] monthBounds(String month) {
        if (month == null || !MONTH_RE.matcher(month).matches()) {
            throw ApiException.unprocessable("month must be YYYY-MM");
        }
        int year;
        int mon;
        try {
            String[] parts = month.split("-", 2);
            year = Integer.parseInt(parts[0]);
            mon = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw ApiException.unprocessable("month must be YYYY-MM");
        }
        if (mon < 1 || mon > 12) {
            throw ApiException.unprocessable("month must be YYYY-MM");
        }
        try {
            LocalDate start = LocalDate.of(year, mon, 1);
            return new LocalDate[]{start, start.plusMonths(1)};
        } catch (java.time.DateTimeException ex) {
            // "0000-01" matches the pattern but is not a real month (Python's date() rejects it too).
            throw ApiException.unprocessable("month must be YYYY-MM");
        }
    }

    /** Python's str.strip() / SQLAlchemy's `or None` idiom. */
    public static String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.trim();
        return stripped.isEmpty() ? null : stripped;
    }

    public static String strip(String value) {
        return value == null ? "" : value.trim();
    }

    /** Python's (q or "").strip(); empty result means "no filter". */
    public static String needle(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static LocalDateTime now() {
        return Times.now();
    }

    public static String isoOrNull(LocalDateTime value) {
        return value == null ? null : Times.iso(value);
    }

    // ---- PATCH-body helpers (Pydantic exclude_unset semantics) ----

    public static boolean hasValue(java.util.Map<String, Object> body, String field) {
        return body != null && body.containsKey(field) && body.get(field) != null;
    }

    public static String bodyString(java.util.Map<String, Object> body, String field, int min, int max) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw ApiException.unprocessable(field + ": Input should be a valid string");
        }
        if (text.length() < min) {
            throw ApiException.unprocessable(field + ": String should have at least " + min + " characters");
        }
        if (max > 0 && text.length() > max) {
            throw ApiException.unprocessable(field + ": String should have at most " + max + " characters");
        }
        return text;
    }

    public static Integer bodyInt(java.util.Map<String, Object> body, String field, Integer min, Integer max) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw ApiException.unprocessable(field + ": Input should be a valid integer");
        }
        int result = number.intValue();
        if (min != null && result < min) {
            throw ApiException.unprocessable(field + ": Input should be greater than or equal to " + min);
        }
        if (max != null && result > max) {
            throw ApiException.unprocessable(field + ": Input should be less than or equal to " + max);
        }
        return result;
    }

    public static java.util.List<Long> bodyLongList(java.util.Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw ApiException.unprocessable(field + ": Input should be a valid list");
        }
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                out.add(number.longValue());
            } else {
                throw ApiException.unprocessable(field + ": Input should be a valid list of integers");
            }
        }
        return out;
    }

    public static java.util.List<String> bodyStringList(java.util.Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw ApiException.unprocessable(field + ": Input should be a valid list");
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) {
                out.add(text);
            } else {
                throw ApiException.unprocessable(field + ": Input should be a valid list of strings");
            }
        }
        return out;
    }

    /** ISO-8601 datetime (with or without offset); naive values are treated as UTC. */
    public static LocalDateTime bodyDateTime(java.util.Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(number.longValue()), java.time.ZoneOffset.UTC);
        }
        if (!(value instanceof String text)) {
            throw ApiException.unprocessable(field + ": Input should be a valid datetime");
        }
        try {
            if (text.endsWith("Z") || text.endsWith("z")) {
                return LocalDateTime.ofInstant(java.time.Instant.parse(text), java.time.ZoneOffset.UTC);
            }
            return java.time.OffsetDateTime.parse(text).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (java.time.format.DateTimeParseException ex) {
                throw ApiException.unprocessable(field + ": Input should be a valid datetime");
            }
        }
    }
}
