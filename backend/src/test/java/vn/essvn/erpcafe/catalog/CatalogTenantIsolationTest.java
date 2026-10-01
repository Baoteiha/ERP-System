package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ModifierGroupRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on catalog lookups (CAT-1, ORG-2): another company's
 * products, categories, modifier groups and branches answer 404 and stay untouched.
 */
class CatalogTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private CompanyRepository companyRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ModifierGroupRepository modifierGroupRepository;
    @Autowired private RecipeRepository recipeRepository;
    @Autowired private ProductBranchAvailabilityRepository availabilityRepository;

    private record Foreign(UUID branchId, UUID categoryId, UUID productId, UUID groupId) {
    }

    private Foreign seedForeign() {
        int n = SEQ.incrementAndGet();
        Company b = TenantContext.runAsSystem(
                () -> companyRepository.save(new Company("Cat Other %d".formatted(n), "CTB%d".formatted(n))));
        return TenantContext.runAs(b.getId(), () -> {
            Branch branch = branchRepository.save(new Branch("B Branch", "CTBB%d".formatted(n)));
            Category cat = categoryRepository.save(new Category("Foreign Cat %d".formatted(n), 0));
            Product product = productRepository.save(new Product(cat.getId(),
                    "Foreign Prod %d".formatted(n), "FSKU%d".formatted(n), Money.of(BigDecimal.TEN)));
            ModifierGroup group = modifierGroupRepository.save(
                    new ModifierGroup("Foreign Group %d".formatted(n), 0, 1));
            return new Foreign(branch.getId(), cat.getId(), product.getId(), group.getId());
        });
    }

    private String admin() throws Exception {
        return authHeader(adminToken());
    }

    private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void productEndpoints_rejectAnotherCompanysProduct() throws Exception {
        Foreign f = seedForeign();
        String id = f.productId().toString();
        String body = "{\"categoryId\":\"%s\",\"name\":\"Hijacked\",\"sku\":\"HJ\",\"basePrice\":1,\"active\":false}"
                .formatted(f.categoryId());

        expectNotFound(get("/api/v1/products/" + id));
        expectNotFound(put("/api/v1/products/" + id).content(body));
        expectNotFound(delete("/api/v1/products/" + id));
        expectNotFound(get("/api/v1/products/" + id + "/availability"));
        expectNotFound(put("/api/v1/products/" + id + "/recipe").content("{\"lines\":[]}"));
        expectNotFound(get("/api/v1/products/" + id + "/recipe"));
        expectNotFound(put("/api/v1/products/" + id + "/modifier-groups")
                .content("{\"modifierGroupIds\":[]}"));
        expectNotFound(put("/api/v1/products/" + id + "/availability")
                .content("{\"branchId\":\"%s\",\"available\":true}".formatted(f.branchId())));

        Product after = TenantContext.runAsSystem(() -> productRepository.findById(f.productId())).orElseThrow();
        assertThat(after.getName()).startsWith("Foreign Prod");
        assertThat(after.isActive()).isTrue();
        assertThat(TenantContext.runAsSystem(() -> recipeRepository.findByProductId(f.productId()))).isEmpty();
        assertThat(TenantContext.runAsSystem(() -> availabilityRepository.findByProductId(f.productId()))).isEmpty();
    }

    @Test
    void categoryEndpoints_rejectAnotherCompanysCategory() throws Exception {
        Foreign f = seedForeign();
        String id = f.categoryId().toString();

        expectNotFound(get("/api/v1/categories/" + id));
        expectNotFound(put("/api/v1/categories/" + id)
                .content("{\"name\":\"Hijacked\",\"displayOrder\":1,\"active\":false}"));
        expectNotFound(delete("/api/v1/categories/" + id));

        Category after = TenantContext.runAsSystem(() -> categoryRepository.findById(f.categoryId())).orElseThrow();
        assertThat(after.getName()).startsWith("Foreign Cat");
        assertThat(after.isActive()).isTrue();
    }

    @Test
    void modifierGroupEndpoints_rejectAnotherCompanysGroup() throws Exception {
        Foreign f = seedForeign();
        String id = f.groupId().toString();

        expectNotFound(get("/api/v1/modifier-groups/" + id));
        expectNotFound(post("/api/v1/modifier-groups/" + id + "/modifiers")
                .content("{\"name\":\"X\",\"priceDelta\":1,\"displayOrder\":0}"));
        expectNotFound(delete("/api/v1/modifier-groups/" + id));

        ModifierGroup after = TenantContext.runAsSystem(() -> modifierGroupRepository.findById(f.groupId()))
                .orElseThrow();
        assertThat(after.getModifiers()).isEmpty();
    }

    @Test
    void ownProduct_rejectsAnotherCompanysBranchAndReferences() throws Exception {
        Foreign f = seedForeign();
        int n = SEQ.incrementAndGet();
        String catA = JsonPath.read(mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CatA%d\",\"displayOrder\":0}".formatted(n)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String prodA = JsonPath.read(mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"%s\",\"name\":\"ProdA%d\",\"sku\":\"PA%d\",\"basePrice\":5}"
                                .formatted(catA, n, n)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        // B's branch, B's category and B's modifier group are all invisible to company A.
        expectNotFound(put("/api/v1/products/" + prodA + "/availability")
                .content("{\"branchId\":\"%s\",\"available\":true}".formatted(f.branchId())));
        expectNotFound(put("/api/v1/products/" + prodA + "/modifier-groups")
                .content("{\"modifierGroupIds\":[\"%s\"]}".formatted(f.groupId())));
        expectNotFound(put("/api/v1/products/" + prodA).content(
                "{\"categoryId\":\"%s\",\"name\":\"ProdA%d\",\"sku\":\"PA%d\",\"basePrice\":5,\"active\":true}"
                        .formatted(f.categoryId(), n, n)));
        expectNotFound(post("/api/v1/products").content(
                "{\"categoryId\":\"%s\",\"name\":\"New%d\",\"sku\":\"NW%d\",\"basePrice\":5}"
                        .formatted(f.categoryId(), n, n)));

        assertThat(TenantContext.runAsSystem(() -> availabilityRepository.findByProductId(UUID.fromString(prodA))))
                .isEmpty();
    }
}
