package us.minitv.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Time helpers. The database stores UTC instants in DATETIME columns; the
 * dashboard buckets by America/Los_Angeles like the Python service did.
 */
public final class Times {

    public static final ZoneId UTC = ZoneOffset.UTC;
    public static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Times() {
    }

    /** Current UTC instant, truncated to the column precision. */
    public static LocalDateTime now() {
        return LocalDateTime.now(UTC);
    }

    public static LocalDateTime fromInstant(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }

    /** Treat a stored naive DATETIME as UTC. */
    public static ZonedDateTime asUtc(LocalDateTime value) {
        return value == null ? null : value.atZone(UTC);
    }

    public static LocalDate businessDay(LocalDateTime storedUtc) {
        if (storedUtc == null) {
            return null;
        }
        return storedUtc.atZone(UTC).withZoneSameInstant(LA).toLocalDate();
    }

    public static LocalDate laToday() {
        return ZonedDateTime.now(LA).toLocalDate();
    }

    /** UTC bounds of a Los Angeles business day, DST-aware. */
    public static LocalDateTime dayStartUtc(LocalDate day) {
        return ZonedDateTime.of(day.atStartOfDay(), LA).withZoneSameInstant(UTC).toLocalDateTime();
    }

    /** ISO-8601 with the offset FastAPI emitted, e.g. 2026-10-14T12:00:00+00:00. */
    public static String iso(LocalDateTime storedUtc) {
        return storedUtc == null ? null : storedUtc.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static String isoDate(LocalDate day) {
        return day == null ? null : day.toString();
    }
}
