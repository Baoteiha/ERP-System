package vn.essvn.erpcafe.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enables JPA auditing so {@code @CreatedBy}/{@code @LastModifiedBy} and the
 * timestamp columns are populated automatically.
 *
 * <p>The auditor is the authenticated user's name (email); unauthenticated
 * actions (e.g. startup seeding) are recorded as {@code "system"}.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return Optional.of("system");
            }
            return Optional.ofNullable(auth.getName());
        };
    }
}
