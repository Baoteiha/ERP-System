package vn.essvn.erpcafe.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: InputValidationIntegrationTest
 * Purpose: Proves invalid prices, costs, passwords, ranges and omitted active flags are handled at the web edge.
 */
class InputValidationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
            String body) throws Exception {
        return mockMvc.perform(b.header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                .header("X-Branch-Id", branchId())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String created(String url, String body) throws Exception {
        String json = send(post(url), body).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String branchId() throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(me, "$.branchIds").get(0);
    }

    private String newProduct(int n) throws Exception {
        String categoryId = created("/api/v1/categories", "{\"name\":\"Val%d\",\"displayOrder\":1}".formatted(n));
        return created("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Val%d\",\"sku\":\"VAL%d\",\"basePrice\":1000}"
                        .formatted(categoryId, n, n));
    }

    private String productBody(String categoryId, int n, String activePart) {
        return "{\"categoryId\":\"%s\",\"name\":\"Val%d\",\"sku\":\"VAL%d\",\"basePrice\":1000%s}"
                .formatted(categoryId, n, n, activePart);
    }

    private void expectValidation(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void availability_rejectsNegativePriceOverride() throws Exception {
        String productId = newProduct(SEQ.incrementAndGet());

        expectValidation(send(put("/api/v1/products/" + productId + "/availability"),
                "{\"branchId\":\"%s\",\"available\":true,\"priceOverride\":-1}".formatted(branchId())));
    }

    @Test
    void receipt_rejectsNegativeUnitCostOverride() throws Exception {
        expectValidation(send(post("/api/v1/purchase-orders/" + UUID.randomUUID() + "/receive"),
                "{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1,\"unitCostOverride\":-400}]}"
                        .formatted(UUID.randomUUID())));
    }

    @Test
    void createUser_rejectsPasswordOver72Utf8Bytes() throws Exception {
        int n = SEQ.incrementAndGet();

        expectValidation(send(post("/api/v1/users"),
                "{\"email\":\"long%d@val.test\",\"password\":\"%s\"}".formatted(n, "ệ".repeat(25))));
    }

    @Test
    void createUser_acceptsPasswordOf72AsciiBytes() throws Exception {
        int n = SEQ.incrementAndGet();

        send(post("/api/v1/users"),
                "{\"email\":\"ok%d@val.test\",\"password\":\"%s\"}".formatted(n, "a".repeat(72)))
                .andExpect(status().isCreated());
    }

    @Test
    void modifierGroup_rejectsMinSelectAboveMaxSelect() throws Exception {
        expectValidation(send(post("/api/v1/modifier-groups"),
                "{\"name\":\"Bad%d\",\"minSelect\":3,\"maxSelect\":1}".formatted(SEQ.incrementAndGet())));
    }

    @Test
    void modifier_rejectsRecipeDeltaWithoutIngredientId() throws Exception {
        String groupId = created("/api/v1/modifier-groups",
                "{\"name\":\"Grp%d\",\"minSelect\":0,\"maxSelect\":1}".formatted(SEQ.incrementAndGet()));

        expectValidation(send(post("/api/v1/modifier-groups/" + groupId + "/modifiers"),
                "{\"name\":\"Large\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[{\"quantity\":7,\"unit\":\"g\"}]}"));
    }

    @Test
    void recipe_rejectsZeroQuantityLine() throws Exception {
        String productId = newProduct(SEQ.incrementAndGet());

        expectValidation(send(put("/api/v1/products/" + productId + "/recipe"),
                "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":0,\"unit\":\"g\"}]}"
                        .formatted(UUID.randomUUID())));
    }

    @Test
    void productUpdate_omittingActiveKeepsCurrentValue() throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = created("/api/v1/categories", "{\"name\":\"Act%d\",\"displayOrder\":1}".formatted(n));
        String productId = created("/api/v1/products", productBody(categoryId, n, ""));

        send(put("/api/v1/products/" + productId), productBody(categoryId, n, ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        send(put("/api/v1/products/" + productId), productBody(categoryId, n, ",\"active\":false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        send(put("/api/v1/products/" + productId), productBody(categoryId, n, ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void employeeUpdate_omittingActiveKeepsCurrentValue() throws Exception {
        int n = SEQ.incrementAndGet();
        String employeeId = created("/api/v1/staff/employees", "{\"fullName\":\"Val Emp %d\"}".formatted(n));

        send(put("/api/v1/staff/employees/" + employeeId), "{\"fullName\":\"Val Emp %d\",\"active\":false}".formatted(n))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        send(put("/api/v1/staff/employees/" + employeeId), "{\"fullName\":\"Val Emp %d\"}".formatted(n))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }
}
