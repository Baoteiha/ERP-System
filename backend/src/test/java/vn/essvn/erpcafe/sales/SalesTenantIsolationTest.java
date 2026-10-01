package vn.essvn.erpcafe.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderStatus;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant and branch isolation on order lookups (SAL-1, CAT-1).
 *
 * <p>Orders are branch-scoped: another company's order, and another branch of
 * the caller's own company, must both answer 404 and stay untouched.
 */
class SalesTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private CompanyRepository companyRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserBranchAccessRepository accessRepository;

    private UUID seedOpenOrder(UUID companyId, UUID branchId, int n) {
        Product product = TenantContext.runAs(companyId, () -> {
            Category category = categoryRepository.save(new Category("Cat %d".formatted(n), 0));
            return productRepository.save(new Product(category.getId(), "Prod %d".formatted(n),
                    "SKU-S%d".formatted(n), Money.of(BigDecimal.TEN)));
        });
        Order order = new Order(branchId, null, OrderType.DINE_IN, BigDecimal.ZERO);
        order.addLine(new PricedLine(product.getId(), product.getName(), Money.of(BigDecimal.TEN), List.of(),
                Money.of(BigDecimal.TEN)), 1, BigDecimal.ZERO);
        return orderRepository.save(order).getId();
    }

    private String ownBranch() throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<java.util.List<String>>read(me, "$.branchIds").get(0);
    }

    private void assertEveryActionIs404(String orderId, String branchHeader, String token) throws Exception {
        String[][] actions = {
                {"GET", ""}, {"POST", "/payments"}, {"POST", "/complete"},
                {"POST", "/cancel"}, {"POST", "/void"}, {"POST", "/refund"}};
        for (String[] a : actions) {
            var request = a[0].equals("GET") ? get("/api/v1/orders/" + orderId)
                    : post("/api/v1/orders/" + orderId + a[1]);
            request.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchHeader);
            if (a[1].equals("/payments")) {
                request.contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10}");
            }
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    private void assertUnchanged(UUID orderId) {
        Order after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(after.getPayments()).isEmpty();
    }

    @Test
    void orderActions_rejectAnotherCompanysOrder() throws Exception {
        int n = SEQ.incrementAndGet();
        Company b = companyRepository.save(new Company("Sales Other %d".formatted(n), "SLB%d".formatted(n)));
        Branch branchB = TenantContext.runAs(b.getId(),
                () -> branchRepository.save(new Branch("B Branch", "SLBB%d".formatted(n))));
        UUID orderB = seedOpenOrder(b.getId(), branchB.getId(), n);

        assertEveryActionIs404(orderB.toString(), ownBranch(), adminToken());

        assertUnchanged(orderB);
    }

    @Test
    void orderActions_rejectAnotherBranchOfTheSameCompany() throws Exception {
        int n = SEQ.incrementAndGet();
        String own = ownBranch();
        UUID companyA = TenantContext.runAsSystem(
                () -> branchRepository.findById(UUID.fromString(own)).orElseThrow().getCompanyId());
        Branch second = TenantContext.runAs(companyA,
                () -> branchRepository.save(new Branch("Second", "SLS%d".formatted(n))));
        UUID adminId = TenantContext.runAsSystem(() -> userRepository.findByEmail("admin@esscafe.vn"))
                .orElseThrow().getId();
        UserBranchAccess existing = accessRepository.findByUserId(adminId).get(0);
        accessRepository.save(new UserBranchAccess(adminId, second.getId(), existing.getRoleId()));
        UUID orderAtSecond = seedOpenOrder(companyA, second.getId(), n);

        // Fresh login so the token's branch set includes the new branch; the header selects the first one.
        String token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(me, "$.branchIds")).contains(second.getId().toString());
        String first = JsonPath.<java.util.List<String>>read(me, "$.branchIds").stream()
                .filter(id -> !id.equals(second.getId().toString())).findFirst().orElseThrow();

        assertEveryActionIs404(orderAtSecond.toString(), first, token);
        assertUnchanged(orderAtSecond);
        // Selecting the owning branch works.
        mockMvc.perform(get("/api/v1/orders/" + orderAtSecond)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", second.getId().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void createOrder_rejectsAnotherCompanysProduct() throws Exception {
        int n = SEQ.incrementAndGet();
        Company b = companyRepository.save(new Company("Sales Prod %d".formatted(n), "SLP%d".formatted(n)));
        Product productB = TenantContext.runAs(b.getId(), () -> {
            Category cat = categoryRepository.save(new Category("CatP %d".formatted(n), 0));
            return productRepository.save(new Product(cat.getId(), "ProdP %d".formatted(n),
                    "SKU-P%d".formatted(n), Money.of(BigDecimal.TEN)));
        });

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .header("X-Branch-Id", ownBranch())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}"
                                .formatted(productB.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
