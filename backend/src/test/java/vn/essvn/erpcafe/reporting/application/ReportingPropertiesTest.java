package vn.essvn.erpcafe.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Class: ReportingPropertiesTest
 * Purpose: Proves report days are cut only in a named time zone, never at an offset the database would read with the opposite sign.
 */
class ReportingPropertiesTest {

    private static ReportingProperties bind(String zone) {
        return new Binder(new MapConfigurationPropertySource(Map.of("app.reporting.zone", zone)))
                .bind("app.reporting", ReportingProperties.class).get();
    }

    @Test
    void anOffset_isRefused_inEveryWayItCanBeWritten() {
        for (String offset : List.of("+07:00", "UTC+7", "GMT+7", "UT-05:00", "Z")) {
            assertThatThrownBy(() -> new ReportingProperties(ZoneId.of(offset), 366))
                    .as(offset)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Asia/Ho_Chi_Minh");
        }
    }

    @Test
    void anOffset_failsTheBindingTheAppStartsWith() {
        assertThatThrownBy(() -> bind("+07:00")).isInstanceOf(BindException.class);
    }

    @Test
    void aRegion_isAccepted() {
        assertThat(bind("Asia/Ho_Chi_Minh").zone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(new ReportingProperties(ZoneId.of("UTC"), 366).zone().getId()).isEqualTo("UTC");
        assertThat(new ReportingProperties(ZoneId.of("Etc/GMT-7"), 366).zone().getId()).isEqualTo("Etc/GMT-7");
    }
}
