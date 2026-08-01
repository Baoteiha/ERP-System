package vn.essvn.erpcafe.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Places the transaction advisor at a low (outer) order so the RLS tenant aspect
 * ({@code TenantRlsAspect}, at {@code LOWEST_PRECEDENCE}) runs <em>inside</em> the
 * transaction — the {@code SET LOCAL app.company_id} must land on the same
 * connection the queries use. {@code proxyTargetClass = true} matches Spring Boot's
 * default so class-based (CGLIB) proxying is preserved.
 */
@Configuration
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
public class RlsConfig {
}
