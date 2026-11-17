package us.minitv.pcatalog.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response-map helper. Python returns plain dicts whose key order is the literal
 * order in the source; {@link LinkedHashMap} keeps the same wire order and
 * Jackson still emits {@code null} values (the Python dicts contain them).
 */
public final class PCatalogOut {

    private PCatalogOut() {
    }

    /** Build an ordered map from alternating key/value arguments (null values allowed). */
    public static Map<String, Object> of(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected alternating key/value arguments");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            payload.put((String) pairs[index], pairs[index + 1]);
        }
        return payload;
    }
}
