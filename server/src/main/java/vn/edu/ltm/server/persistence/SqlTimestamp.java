package vn.edu.ltm.server.persistence;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** UTC instants with fixed nanosecond precision, so SQLite TEXT order is chronological. */
public final class SqlTimestamp {
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private SqlTimestamp() { }

    public static String format(Instant instant) { return FORMAT.format(instant); }

    public static String normalize(String value) {
        return value == null ? null : format(Instant.parse(value));
    }
}
