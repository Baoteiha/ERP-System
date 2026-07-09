package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * End-to-end catalog tests: category/product/modifier/recipe CRUD, price
 * computation (base, modifier delta, per-branch override), and catalog RBAC.
 */
class CatalogFlowIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String ING1 = "11111111-1111-1111-1111-111111111111";
    private static final String ING2 = "22222222-2222-2222-2222-222222222222";

    private String postJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions putJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    private BigDecimal priceAmount(String token, String url) throws Exception {
        String json = mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Object amount = JsonPath.read(json, "$.price.amount");
        return new BigDecimal(String.valueOf(amount));
    }

    @Test
    void fullCatalogFlow_pricingRecipeAndModifiers() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();
        String branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(),
                "$.branchIds")).get(0);

        // category + product (base price 25000)
        String categoryId = id(postJson("/api/v1/categories", token,
                "{\"name\":\"Coffee%d\",\"displayOrder\":1}".formatted(n)));
        String productId = id(postJson("/api/v1/products", token,
                "{\"categoryId\":\"%s\",\"name\":\"Latte%d\",\"sku\":\"LAT%d\",\"basePrice\":25000}".formatted(categoryId, n, n)));

        // modifier group + a "Large" modifier (+5000, +7g coffee)
        String groupId = id(postJson("/api/v1/modifier-groups", token,
                "{\"name\":\"Size%d\",\"minSelect\":1,\"maxSelect\":1}".formatted(n)));
        String modifierId = id(postJson("/api/v1/modifier-groups/" + groupId + "/modifiers", token,
                ("{\"name\":\"Large\",\"priceDelta\":5000,\"displayOrder\":1,"
                        + "\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":7,\"unit\":\"g\"}]}").formatted(ING1)));

        // attach group to product
        putJson("/api/v1/products/" + productId + "/modifier-groups", token,
                "{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId))
                .andExpect(status().isOk());

        // set recipe (single-field SetRecipeRequest must deserialize correctly)
        putJson("/api/v1/products/" + productId + "/recipe", token,
                ("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":18,\"unit\":\"g\"},"
                        + "{\"ingredientId\":\"%s\",\"quantity\":200,\"unit\":\"ml\"}]}").formatted(ING1, ING2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2));

        // pricing: base, base + modifier
        assertThat(priceAmount(token, "/api/v1/products/" + productId + "/price"))
                .isEqualByComparingTo("25000");
        assertThat(priceAmount(token, "/api/v1/products/" + productId + "/price?modifierIds=" + modifierId))
                .isEqualByComparingTo("30000");

        // per-branch override 28000
        putJson("/api/v1/products/" + productId + "/availability", token,
                "{\"branchId\":\"%s\",\"available\":true,\"priceOverride\":28000}".formatted(branchId))
                .andExpect(status().isOk());
        assertThat(priceAmount(token, "/api/v1/products/" + productId + "/price?branchId=" + branchId))
                .isEqualByComparingTo("28000");
        assertThat(priceAmount(token,
                "/api/v1/products/" + productId + "/price?branchId=" + branchId + "&modifierIds=" + modifierId))
                .isEqualByComparingTo("33000");

        // reads: product exposes the attached group; group exposes the nested modifier
        mockMvc.perform(get("/api/v1/products/" + productId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice.amount").value(25000))
                .andExpect(jsonPath("$.modifierGroupIds[0]").value(groupId));
        mockMvc.perform(get("/api/v1/modifier-groups/" + groupId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modifiers[0].name").value("Large"))
                .andExpect(jsonPath("$.modifiers[0].priceDelta.amount").value(5000));
    }

    @Test
    void cashier_hasCatalogReadButNotWrite() throws Exception {
        String adminToken = adminToken();
        int n = SEQ.incrementAndGet();

        // create a cashier and grant CASHIER at the admin's branch
        String userId = id(postJson("/api/v1/users", adminToken,
                "{\"email\":\"cat-cashier%d@esscafe.vn\",\"password\":\"cashier123\",\"fullName\":\"C\"}".formatted(n)));
        String rolesJson = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andReturn().getResponse().getContentAsString();
        String cashierRoleId = ((List<String>) JsonPath.read(rolesJson, "$[?(@.name=='CASHIER')].id")).get(0);
        String branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                        .andReturn().getResponse().getContentAsString(),
                "$.branchIds")).get(0);
        postJson("/api/v1/users/" + userId + "/access", adminToken,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, cashierRoleId));

        String cashierToken = accessToken("cat-cashier%d@esscafe.vn".formatted(n), "cashier123");

        mockMvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\",\"displayOrder\":1}"))
                .andExpect(status().isForbidden());
    }
}
