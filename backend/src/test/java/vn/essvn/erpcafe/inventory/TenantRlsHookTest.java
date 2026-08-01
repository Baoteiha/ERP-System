package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.inventory.application.TenantProbe;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Verifies the app-side half of RLS: {@code TenantRlsAspect} sets {@code app.company_id}
 * from the authenticated principal, inside the transaction, on the connection the
 * query uses. (RLS enforcement itself is proven by {@link IngredientRlsPolicyTest}.)
 */
class TenantRlsHookTest extends AbstractIntegrationTest {

    @Autowired
    private TenantProbe probe;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsCompanyIdFromAuthenticatedUser() {
        UUID company = UUID.randomUUID();
        authenticateWithCompany(company);
        assertThat(probe.currentTenant()).isEqualTo(company.toString());
    }

    @Test
    void leavesTenantUnsetWhenNoUser() {
        SecurityContextHolder.clearContext();
        // On a pooled connection the placeholder reads back as "" once it has been
        // SET LOCAL in a prior transaction; both null and "" mean "no tenant".
        assertThat(probe.currentTenant()).isNullOrEmpty();
    }

    private void authenticateWithCompany(UUID companyId) {
        AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), "probe@test.local", companyId,
                Set.of(UUID.randomUUID()), Set.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
