package vn.essvn.erpcafe.reporting.application;

import java.time.ZoneId;

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
}
