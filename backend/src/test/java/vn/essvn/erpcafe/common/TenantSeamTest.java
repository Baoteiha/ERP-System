package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ModifierGroupRepository;
import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.UnauthorizedException;
import vn.essvn.erpcafe.identity.security.AccessClaims;
import vn.essvn.erpcafe.identity.security.JwtService;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: TenantSeamTest
 * Purpose: Proves the tenant seam: Hibernate filters reads by, and stamps inserts with, the company in TenantContext.
 */
class TenantSeamTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private CompanyRepository companyRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ModifierGroupRepository groupRepository;
    @Autowired private ModifierRepository modifierRepository;
    @Autowired private JwtService jwtService;

    private record Tenant(UUID companyId, UUID categoryId, UUID productId, UUID modifierId,
            String categoryName, String sku) {
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** A company with one category, product and modifier, written inside that company's tenant. */
    private Tenant seedCompany() {
        int n = SEQ.incrementAndGet();
        Company company = companyRepository.save(new Company("Seam %d".formatted(n), "SEAM%d".formatted(n)));
        return TenantContext.runAs(company.getId(), () -> {
            Category category = categoryRepository.save(new Category("Seam Drinks %d".formatted(n), 1));
            Product product = productRepository.save(new Product(category.getId(), "Latte",
                    "SEAM-LAT-%d".formatted(n), Money.of(BigDecimal.valueOf(45_000))));
            ModifierGroup group = new ModifierGroup("Size", 0, 1);
            group.getModifiers().add(new Modifier("Large", Money.of(BigDecimal.valueOf(5_000)), 0));
            UUID modifierId = groupRepository.save(group).getModifiers().get(0).getId();
            return new Tenant(company.getId(), category.getId(), product.getId(), modifierId,
                    category.getName(), product.getSku());
        });
    }

    private Product newProduct(Tenant tenant, String sku) {
        return new Product(tenant.categoryId(), "Mocha " + sku, sku, Money.of(BigDecimal.valueOf(50_000)));
    }

    @Test
    void findById_ofAnotherCompanysProduct_isEmpty() {
        Tenant a = seedCompany();
        Tenant b = seedCompany();

        TenantContext.set(a.companyId());

        assertThat(productRepository.findById(b.productId())).isEmpty();
        assertThat(productRepository.findById(a.productId())).isPresent();
        assertThat(productRepository.existsById(b.productId())).isFalse();
        assertThat(productRepository.findAllById(List.of(a.productId(), b.productId())))
                .extracting(Product::getId).containsExactly(a.productId());
    }

    @Test
    void queries_seeOnlyTheCurrentCompanysRows() {
        Tenant a = seedCompany();
        Tenant b = seedCompany();

        TenantContext.set(a.companyId());

        assertThat(productRepository.findAll()).extracting(Product::getCompanyId).containsOnly(a.companyId());
        assertThat(productRepository.findAll()).extracting(Product::getId)
                .contains(a.productId()).doesNotContain(b.productId());
    }

    @Test
    void queryRootedInATenantEntity_isFilteredForTheChildItSelects() {
        Tenant a = seedCompany();
        Tenant b = seedCompany();

        TenantContext.set(a.companyId());

        // a modifier has no company of its own: only its group, the query's root, is filtered
        assertThat(modifierRepository.findThroughGroup(b.modifierId())).isEmpty();
        assertThat(modifierRepository.findThroughGroup(a.modifierId())).isPresent();
    }

    @Test
    void save_stampsTheCurrentCompanyOnARowBuiltWithoutOne() {
        Tenant a = seedCompany();

        TenantContext.set(a.companyId());
        Product saved = productRepository.save(newProduct(a, "MOC"));

        assertThat(saved.getCompanyId()).isEqualTo(a.companyId());
        UUID stored = TenantContext.runAsSystem(() -> productRepository.findById(saved.getId()).orElseThrow())
                .getCompanyId();
        assertThat(stored).isEqualTo(a.companyId());
    }

    @Test
    void save_refusesARowAssignedAnotherCompany() {
        Tenant a = seedCompany();
        Tenant b = seedCompany();
        Product forged = newProduct(a, "FRG");
        ReflectionTestUtils.setField(forged, "companyId", b.companyId());

        TenantContext.set(a.companyId());

        assertThatThrownBy(() -> productRepository.save(forged))
                .hasStackTraceContaining("differs from current tenant id");
        assertThat(TenantContext.runAsSystem(() -> productRepository.findAll()))
                .extracting(Product::getSku).doesNotContain("FRG");
    }

    @Test
    void save_outsideACompany_isRefused() {
        Tenant a = seedCompany();

        assertThatThrownBy(() -> TenantContext.runAsSystem(() -> productRepository.save(newProduct(a, "SYS"))))
                .hasStackTraceContaining("can only be written inside its company's tenant");
        assertThatThrownBy(() -> productRepository.save(newProduct(a, "NONE")))
                .hasStackTraceContaining("can only be written inside its company's tenant");
    }

    @Test
    void runAsSystem_seesEveryCompany() {
        Tenant a = seedCompany();
        Tenant b = seedCompany();
        TenantContext.set(a.companyId());

        List<UUID> seen = TenantContext.runAsSystem(() -> productRepository
                .findAllById(List.of(a.productId(), b.productId())).stream().map(Product::getCompanyId).toList());

        assertThat(seen).containsExactlyInAnyOrder(a.companyId(), b.companyId());
        assertThat(TenantContext.current()).contains(a.companyId());
    }

    @Test
    void noTenant_seesNoTenantRows() {
        Tenant a = seedCompany();

        assertThat(TenantContext.current()).isEmpty();
        assertThat(productRepository.findById(a.productId())).isEmpty();
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

    @Test
    void request_restoresTheScopeItInterrupted() throws Exception {
        UUID company = UUID.randomUUID();
        String token = adminToken();
        TenantContext.set(company);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk());

        assertThat(TenantContext.current()).contains(company);
    }

    @Test
    void sentinelTenants_areNeverACompany() {
        for (UUID sentinel : List.of(TenantContext.NO_TENANT_ID, TenantContext.SYSTEM_TENANT_ID)) {
            assertThatThrownBy(() -> TenantContext.set(sentinel)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TenantContext.enter(sentinel)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TenantContext.runAs(sentinel, () -> "unreachable"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(TenantContext.current()).isEmpty();
            assertThat(TenantContext.isSystem()).isFalse();
        }
    }

    @Test
    void tokenNamingASentinelCompany_isUnauthorized() throws Exception {
        for (UUID sentinel : List.of(TenantContext.NO_TENANT_ID, TenantContext.SYSTEM_TENANT_ID)) {
            String token = jwtService.generateAccessToken(
                    new AccessClaims(UUID.randomUUID(), "sentinel@test.local", sentinel, Map.of()));

            mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void namesAndSkusAreUniqueOnlyWithinACompany() throws Exception {
        Tenant other = seedCompany();
        String token = adminToken();

        // the existsBy… pre-checks see only the caller's company, as the partial unique indexes do
        String category = mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"displayOrder\":1}".formatted(other.categoryName())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"%s\",\"name\":\"Latte\",\"sku\":\"%s\",\"basePrice\":45000}"
                                .formatted(JsonPath.<String>read(category, "$.id"), other.sku())))
                .andExpect(status().isCreated());

        Product theirs = TenantContext.runAsSystem(() -> productRepository.findById(other.productId())).orElseThrow();
        assertThat(theirs.getCompanyId()).isEqualTo(other.companyId());
    }
}
