package vn.essvn.erpcafe.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: BranchTaxRateIntegrationTest
 * Purpose: Proves a branch stores a validated tax rate and every order at that branch uses it, ignoring the client's.
 */
class BranchTaxRateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BranchRepository branches;

    private String token;
    private String branchId;

    @AfterEach
    void restoreSeededBranchRate() {
        // the seeded admin branch is shared by every test class: leave it at the default rate
        if (branchId != null) {
            TenantContext.runAsSystem(() -> {
                Branch branch = branches.findById(UUID.fromString(branchId)).orElseThrow();
                branch.setTaxRate(BigDecimal.ZERO);
                branches.save(branch);
            });
        }
    }

    private void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private BigDecimal storedRate() {
        return TenantContext.runAsSystem(() -> branches.findById(UUID.fromString(branchId)).orElseThrow().getTaxRate());
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions updateBranch(String taxRateJson) throws Exception {
        String current = mockMvc.perform(authed(get("/api/v1/branches/" + branchId))).andReturn().getResponse().getContentAsString();
        String name = JsonPath.read(current, "$.name");
        String code = JsonPath.read(current, "$.code");
        return mockMvc.perform(authed(put("/api/v1/branches/" + branchId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"code\":\"%s\",\"taxRate\":%s}".formatted(name, code, taxRateJson)));
    }

    @Test
    void updateBranch_persistsTaxRate() throws Exception {
        login();

        updateBranch("0.08").andExpect(status().isOk()).andExpect(jsonPath("$.taxRate").value(0.08));

        assertThat(storedRate()).isEqualByComparingTo("0.08");
    }

    @Test
    void updateBranch_rejectsRateOutsideZeroToOne() throws Exception {
        login();
        updateBranch("0.08").andExpect(status().isOk());

        updateBranch("1.5").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        updateBranch("-0.1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));

        assertThat(storedRate()).isEqualByComparingTo("0.08");
    }

    @Test
    void order_usesTheBranchRate_andIgnoresTheClients() throws Exception {
        login();
        updateBranch("0.08").andExpect(status().isOk());
        String s = UUID.randomUUID().toString().substring(0, 8);
        String categoryId = JsonPath.read(mockMvc.perform(authed(post("/api/v1/categories")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tax-%s\",\"displayOrder\":1}".formatted(s)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String productId = JsonPath.read(mockMvc.perform(authed(post("/api/v1/products")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":\"%s\",\"name\":\"Tea-%s\",\"sku\":\"TAX-%s\",\"basePrice\":25000}".formatted(categoryId, s, s)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(authed(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderType\":\"TAKEAWAY\",\"taxRate\":0.5,\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]}"
                                .formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxRate").value(0.08))
                .andExpect(jsonPath("$.taxTotal").value(4000))
                .andExpect(jsonPath("$.grandTotal").value(54000));
    }
}
