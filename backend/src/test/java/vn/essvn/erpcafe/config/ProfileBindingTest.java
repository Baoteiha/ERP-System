package vn.essvn.erpcafe.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import jakarta.validation.Validation;
import vn.essvn.erpcafe.reporting.application.ReportingProperties;
import vn.essvn.erpcafe.staff.application.StaffProperties;

/**
 * Class: ProfileBindingTest
 * Purpose: Binds the shipped configuration files under the prod and dev profiles, as the app would at startup.
 */
class ProfileBindingTest {

    /** What a production deployment must supply in its environment. */
    private static final Map<String, Object> PROD_ENVIRONMENT = Map.of(
            "DB_URL", "jdbc:postgresql://db.example.test:5432/erpcafe",
            "DB_APP_PASSWORD", "app-password",
            "DB_OWNER_USER", "erpcafe_owner",
            "DB_OWNER_PASSWORD", "owner-password",
            "JWT_SECRET", "a-production-signing-key-of-at-least-32-bytes",
            "ADMIN_EMAIL", "owner@cafe.example.test",
            "APP_PUBLIC_URL", "https://erp.cafe.example.test");

    /** application.yml under application-{profile}.yml, with the environment above both, as Boot orders them. */
    private static Binder binder(String profile, Map<String, Object> environment) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("environment", environment));
        YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();
        for (String file : new String[] {"application-" + profile + ".yml", "application.yml"}) {
            for (PropertySource<?> source : yaml.load(file, new ClassPathResource(file))) {
                sources.addLast(source);
            }
        }
        return Binder.get(env);
    }

    private static <T> T bind(Binder binder, String prefix, Class<T> type) {
        ValidationBindHandler validation = new ValidationBindHandler(
                new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator()));
        return binder.bindOrCreate(prefix, Bindable.of(type), validation);
    }

    @Test
    void theProdProfile_bindsEverySetting() throws IOException {
        Binder prod = binder("prod", PROD_ENVIRONMENT);

        SecurityProperties security = bind(prod, "app.security", SecurityProperties.class);
        assertThat(security.jwt().activeKey().kid()).isEqualTo("primary");
        // off until a real mail adapter exists (the logging one serves dev and test only)
        assertThat(security.passwordReset().enabled()).isFalse();
        assertThat(security.passwordReset().ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(security.passwordReset().link()).isEqualTo("https://erp.cafe.example.test/reset-password");
        assertThat(bind(prod, "app.datasource.owner", OwnerPoolProperties.class).maximumPoolSize()).isEqualTo(10);
        assertThat(bind(prod, "app.reporting", ReportingProperties.class).zone())
                .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(bind(prod, "app.staff", StaffProperties.class).maxShiftHours()).isEqualTo(16);
    }

    @Test
    void theProdProfile_needsNoPublicUrl_whilePasswordResetIsOff() throws IOException {
        Map<String, Object> withoutPublicUrl = new HashMap<>(PROD_ENVIRONMENT);
        withoutPublicUrl.remove("APP_PUBLIC_URL");

        assertThat(bind(binder("prod", withoutPublicUrl), "app.security", SecurityProperties.class)
                .passwordReset().enabled()).isFalse();
    }

    @Test
    void theDevProfile_bindsEverySetting() throws IOException {
        Binder dev = binder("dev", Map.of());

        SecurityProperties security = bind(dev, "app.security", SecurityProperties.class);
        assertThat(security.jwt().activeKey().kid()).isEqualTo("dev-1");
        assertThat(security.passwordReset().enabled()).isTrue();
        assertThat(security.passwordReset().ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(bind(dev, "app.datasource.owner", OwnerPoolProperties.class).maximumPoolSize()).isEqualTo(10);
    }
}
