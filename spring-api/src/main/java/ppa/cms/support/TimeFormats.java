package ppa.cms.support;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class TimeFormats {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXXXX").withZone(ZoneOffset.UTC);

    private TimeFormats() {
    }

    public static String iso(Instant instant) {
        return instant == null ? null : ISO.format(instant);
    }
}
