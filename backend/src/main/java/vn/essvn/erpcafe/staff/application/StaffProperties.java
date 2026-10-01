package vn.essvn.erpcafe.staff.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class: StaffProperties
 * Purpose: Binds {@code app.staff.*}: the longest a shift may run before a manager has to adjust it.
 */
@ConfigurationProperties(prefix = "app.staff")
public record StaffProperties(@DefaultValue("16") int maxShiftHours) {

    public Duration maxShiftLength() {
        return Duration.ofHours(maxShiftHours);
    }
}
