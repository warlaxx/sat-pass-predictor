package space.nextpass.gcat;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GCAT date, with the precision GCAT gave it and nothing more.
 *
 * <p>GCAT writes {@code 2026 Jul 11 0402:25}, {@code 2026 Jun 19 2200?}, {@code 2026 May?},
 * {@code 1960s?} or {@code 2026 Q3?}: the text says how much is known. {@link #start()} is
 * the beginning of that interval, so it sorts correctly, but it must never be shown
 * without {@link #precision()} — turning {@code 2026 May?} into "1 May 2026" would invent a
 * fact. {@link #uncertain()} is GCAT's trailing {@code ?}. GCAT times are UTC.
 */
public record GcatDate(Instant start, Precision precision, boolean uncertain) {

    public enum Precision { DECADE, YEAR, QUARTER, MONTH, DAY, MINUTE, SECOND }

    // Year, then either a decade mark, a quarter, or month [day [HHMM[:SS]]].
    private static final Pattern FORMAT = Pattern.compile(
            "(\\d{4})(?:(s)|\\s+Q([1-4])|\\s+([A-Z][a-z]{2})(?:\\s+(\\d{1,2})(?:\\s+(\\d{2})(\\d{2})(?::(\\d{2}))?)?)?)?(\\?)?");

    /**
     * Parses a GCAT date column. Returns {@code null} for GCAT's "none" ({@code -} or
     * blank). Throws {@link IllegalArgumentException} for a shape not seen before, so the
     * caller decides whether one odd cell is worth failing an import for.
     */
    public static GcatDate parse(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.equals("-")) {
            return null;
        }
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unknown GCAT date: '" + value + "'");
        }
        int year = Integer.parseInt(m.group(1));
        boolean uncertain = m.group(9) != null;
        if (m.group(2) != null) {
            return new GcatDate(utc(year, 1, 1, 0, 0, 0), Precision.DECADE, uncertain);
        }
        if (m.group(3) != null) {
            int firstMonth = (Integer.parseInt(m.group(3)) - 1) * 3 + 1;
            return new GcatDate(utc(year, firstMonth, 1, 0, 0, 0), Precision.QUARTER, uncertain);
        }
        if (m.group(4) == null) {
            return new GcatDate(utc(year, 1, 1, 0, 0, 0), Precision.YEAR, uncertain);
        }
        int month = month(m.group(4), value);
        if (m.group(5) == null) {
            return new GcatDate(utc(year, month, 1, 0, 0, 0), Precision.MONTH, uncertain);
        }
        int day = Integer.parseInt(m.group(5));
        if (m.group(6) == null) {
            return new GcatDate(utc(year, month, day, 0, 0, 0), Precision.DAY, uncertain);
        }
        int hour = Integer.parseInt(m.group(6));
        int minute = Integer.parseInt(m.group(7));
        if (m.group(8) == null) {
            return new GcatDate(utc(year, month, day, hour, minute, 0), Precision.MINUTE, uncertain);
        }
        int second = Integer.parseInt(m.group(8));
        return new GcatDate(utc(year, month, day, hour, minute, second), Precision.SECOND, uncertain);
    }

    private static int month(String abbreviation, String value) {
        for (Month month : Month.values()) {
            String name = month.name();
            if (name.substring(0, 1).concat(name.substring(1, 3).toLowerCase(Locale.ROOT)).equals(abbreviation)) {
                return month.getValue();
            }
        }
        throw new IllegalArgumentException("Unknown month in GCAT date: '" + value + "'");
    }

    private static Instant utc(int year, int month, int day, int hour, int minute, int second) {
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Impossible GCAT date: " + year + "-" + month + "-" + day, e);
        }
    }
}
