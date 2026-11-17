package us.minitv.admin.flow.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import us.minitv.common.ApiException;
import us.minitv.common.Json;
import us.minitv.security.StaffContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small shared helpers for the admin flow/ops port: FastAPI-shaped maps, JSON
 * column conversions and the two extra role checks the Python routers used.
 */
final class AFFlowOpsSupport {

    /** Seed data the Python module hard-codes (billing/service.py DEFAULT_PLANS). */
    static final List<String[]> DEFAULT_PLANS = List.of(
            new String[]{"weekly", "Weekly", "周卡", "999", "false", "Unlock all", "No ads", "1080p"},
            new String[]{"monthly", "Monthly", "月卡", "1999", "true", "Weekly benefits", "Download"},
            new String[]{"yearly", "Yearly", "年卡", "7999", "false", "Monthly benefits", "Priority support", "Save 58%"});

    private AFFlowOpsSupport() {
    }

    /** Python-dict-shaped map that keeps insertion order and allows null values. */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    static List<Map<String, Object>> mapList() {
        return new ArrayList<>();
    }

    static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    static int asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                throw ApiException.unprocessable("Input should be a valid integer");
            }
        }
        throw ApiException.unprocessable("Input should be a valid integer");
    }

    static boolean toBool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        throw ApiException.unprocessable("Input should be a valid boolean");
    }

    static List<Integer> readIntList(String raw) {
        return Json.read(raw, new TypeReference<List<Integer>>() {
        }, List.of());
    }

    static Map<String, Object> readMap(String raw) {
        return Json.readMap(raw);
    }

    static Object readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Json.read(raw).isMissingNode() ? null : Json.mapper().convertValue(Json.read(raw), Object.class);
    }

    /** require_content_writer: role ∈ {ops, admin}. */
    static void requireContentWriter(StaffContext staff) {
        String role = staff.user().getRole();
        if (!"ops".equals(role) && !"admin".equals(role)) {
            throw new ApiException(403, "role '" + role + "' cannot write content");
        }
    }

    /** require_admin_role: role must be exactly "admin". */
    static void requireAdminRole(StaffContext staff) {
        if (!"admin".equals(staff.user().getRole())) {
            throw new ApiException(403, "admin only");
        }
    }

    /** Python's str(float) for the coin-adjustment ledger business key. */
    static String pythonFloat(double value) {
        BigDecimal plain = new BigDecimal(Double.toString(value));
        return plain.stripTrailingZeros().toPlainString();
    }

    static int daysBetweenInclusive(LocalDate start, LocalDate end) {
        return (int) (end.toEpochDay() - start.toEpochDay()) + 1;
    }

    static LocalDateTime now() {
        return us.minitv.common.Times.now();
    }
}
