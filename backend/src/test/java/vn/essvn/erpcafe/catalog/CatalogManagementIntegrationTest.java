package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: CatalogManagementIntegrationTest
 * Purpose: Pins the catalog edit/delete gaps: in-use deletes, modifier edits, stale recipe saves and batched loading.
 */
class CatalogManagementIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private EntityManagerFactory entityManagerFactory;

    private String token;
    private String branchId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String created(String url, String body) throws Exception {
        String json = mockMvc.perform(authed(post(url)).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String category() throws Exception {
        return created("/api/v1/categories", "{\"name\":\"CM%d\",\"displayOrder\":1}".formatted(SEQ.incrementAndGet()));
    }

    private String product(String categoryId) throws Exception {
        int n = SEQ.incrementAndGet();
        return created("/api/v1/products", "{\"categoryId\":\"%s\",\"name\":\"CMP%d\",\"sku\":\"CMP%d\",\"basePrice\":20000}"
                .formatted(categoryId, n, n));
    }

    private String group() throws Exception {
        return created("/api/v1/modifier-groups",
                "{\"name\":\"CMG%d\",\"minSelect\":0,\"maxSelect\":2}".formatted(SEQ.incrementAndGet()));
    }

    private String modifier(String groupId, int price) throws Exception {
        return created("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"CMM%d\",\"priceDelta\":%d,\"displayOrder\":1}".formatted(SEQ.incrementAndGet(), price));
    }

    private void attach(String productId, String groupId) throws Exception {
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/modifier-groups"))
                .content("{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId))).andExpect(status().isOk());
    }

    private ResultActions order(String productId, String modifierId) throws Exception {
        return mockMvc.perform(authed(post("/api/v1/orders")).content(
                ("{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,"
                        + "\"modifierIds\":[\"%s\"]}]}").formatted(productId, modifierId)));
    }

    @Test
    void deletingACategoryWithProducts_isRefused() throws Exception {
        String categoryId = category();
        product(categoryId);

        mockMvc.perform(authed(delete("/api/v1/categories/" + categoryId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(authed(get("/api/v1/categories/" + categoryId))).andExpect(status().isOk());
    }

    @Test
    void editingAModifierPrice_isUsedByTheNextOrder() throws Exception {
        String groupId = group();
        String modifierId = modifier(groupId, 5000);
        String productId = product(category());
        attach(productId, groupId);

        mockMvc.perform(authed(put("/api/v1/modifier-groups/" + groupId + "/modifiers/" + modifierId)).content(
                "{\"name\":\"Renamed\",\"priceDelta\":9000,\"active\":true,\"displayOrder\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));

        String json = order(productId, modifierId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.lines[0].lineTotal.amount"))))
                .isEqualByComparingTo("29000");
    }

    @Test
    void editingAGroup_changesItsRulesAndRejectsAnInvertedRange() throws Exception {
        String groupId = group();

        mockMvc.perform(authed(put("/api/v1/modifier-groups/" + groupId))
                .content("{\"name\":\"Size\",\"minSelect\":1,\"maxSelect\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Size"))
                .andExpect(jsonPath("$.required").value(true));
        mockMvc.perform(authed(put("/api/v1/modifier-groups/" + groupId))
                .content("{\"name\":\"Size\",\"minSelect\":3,\"maxSelect\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAModifier_makesItUnselectable() throws Exception {
        String groupId = group();
        String modifierId = modifier(groupId, 5000);
        String productId = product(category());
        attach(productId, groupId);

        mockMvc.perform(authed(delete("/api/v1/modifier-groups/" + groupId + "/modifiers/" + modifierId)))
                .andExpect(status().isNoContent());

        order(productId, modifierId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        mockMvc.perform(authed(get("/api/v1/modifier-groups/" + groupId)))
                .andExpect(jsonPath("$.modifiers.length()").value(0));
    }

    @Test
    void aModifierOfAnotherGroup_is404OnEditAndDelete() throws Exception {
        String groupA = group();
        String groupB = group();
        String modifierId = modifier(groupB, 5000);

        mockMvc.perform(authed(delete("/api/v1/modifier-groups/" + groupA + "/modifiers/" + modifierId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/modifier-groups/" + groupA + "/modifiers/" + modifierId))
                .content("{\"name\":\"X\",\"priceDelta\":1,\"active\":true,\"displayOrder\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(get("/api/v1/modifier-groups/" + groupB)))
                .andExpect(jsonPath("$.modifiers.length()").value(1));
    }

    @Test
    void deletingAGroupAttachedToAProduct_isRefused() throws Exception {
        String groupId = group();
        attach(product(category()), groupId);

        mockMvc.perform(authed(delete("/api/v1/modifier-groups/" + groupId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(authed(get("/api/v1/modifier-groups/" + groupId))).andExpect(status().isOk());
    }
}
