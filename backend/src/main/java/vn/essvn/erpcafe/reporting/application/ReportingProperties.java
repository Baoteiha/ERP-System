package vn.essvn.erpcafe.reporting.application;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class: ReportingProperties
 * Purpose: Binds {@code app.reporting.*}: the zone report days are cut in and the longest period a report may span.
 */
@ConfigurationProperties(prefix = "app.reporting")
public record ReportingProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") ZoneId zone,
        @DefaultValue("366") int maxRangeDays) {

    /** "UTC+7", "GMT-05:00", "UT+1": an offset with a prefix, which Java parses into a fixed-offset zone. */
    private static final Pattern PREFIXED_OFFSET = Pattern.compile("(UTC|GMT|UT)[+-].*");

    // PostgreSQL reads an offset ("+07:00", "UTC+7") with the POSIX sign, the opposite of Java's, so the
    // backfill and the app would cut days 14 hours apart: only a named region is accepted.
    public ReportingProperties {
        if (zone instanceof ZoneOffset || PREFIXED_OFFSET.matcher(zone.getId()).matches()) {
            throw new IllegalArgumentException("app.reporting.zone must name a region such as Asia/Ho_Chi_Minh,"
                    + " not an offset: " + zone.getId());
        }
    }
}
