package us.minitv.partner;

/** Small string helpers matching Python's {@code str} semantics used by the partner surface. */
public final class PartnerText {

    private PartnerText() {
    }

    /** Python {@code str.strip()}: trims Unicode whitespace (Java {@code String.strip()}). */
    public static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** Python truthiness for strings: {@code bool(value)}. */
    public static boolean truthy(String value) {
        return value != null && !value.isEmpty();
    }

    /** Python {@code len(str)}: counts Unicode code points, not UTF-16 units. */
    public static int pyLen(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** Python {@code ", ".join(map(str, numbers))}. */
    public static String joinInts(java.util.List<Integer> numbers) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < numbers.size(); index++) {
            if (index > 0) {
                joined.append(", ");
            }
            joined.append(numbers.get(index));
        }
        return joined.toString();
    }

    /**
     * Python {@code datetime.now(timezone.utc).timestamp()} rendered into a cache
     * buster: epoch seconds with a fractional part when the clock has millis.
     */
    public static String epochTimestamp() {
        long millis = System.currentTimeMillis();
        long seconds = Math.floorDiv(millis, 1000L);
        long fraction = Math.floorMod(millis, 1000L);
        if (fraction == 0L) {
            return seconds + ".0";
        }
        String digits = String.format("%03d", fraction);
        while (digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        return seconds + "." + digits;
    }
}
