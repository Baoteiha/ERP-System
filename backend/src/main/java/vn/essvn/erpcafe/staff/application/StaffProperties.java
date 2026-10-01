package vn.essvn.erpcafe.staff.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class: StaffProperties
 * Purpose: Binds {@code app.staff.*}: the longest a shift may run before a manager has to adjust it, and the sanity bound on an adjusted shift.
 */
@ConfigurationProperties(prefix = "app.staff")
public record StaffProperties(
        @DefaultValue("16") int maxShiftHours,
        @DefaultValue("24") int maxAdjustedShiftHours) {

    public Duration maxShiftLength() {
        return Duration.ofHours(maxShiftHours);
    }

    public Duration maxAdjustedShiftLength() {
        return Duration.ofHours(maxAdjustedShiftHours);
    }
}
