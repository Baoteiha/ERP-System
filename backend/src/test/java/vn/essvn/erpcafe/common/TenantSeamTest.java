package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.UnauthorizedException;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: TenantSeamTest
 * Purpose: Proves the tenant seam: Hibernate filters reads by, and stamps inserts with, the company in TenantContext.
 */
class TenantSeamTest extends AbstractIntegrationTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runAsSystem_restoresTheTenantItInterrupted() {
        UUID company = UUID.randomUUID();
        TenantContext.set(company);

        boolean insideWasSystem = TenantContext.runAsSystem(TenantContext::isSystem);

        assertThat(insideWasSystem).isTrue();
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.current()).contains(company);
    }

    @Test
    void require_withoutACompany_isUnauthorized() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> TenantContext.runAsSystem(TenantContext::require))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void request_leavesNoTenantOnTheThread() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk());

        assertThat(TenantContext.current()).isEmpty();
        assertThat(TenantContext.isSystem()).isFalse();
    }
}
