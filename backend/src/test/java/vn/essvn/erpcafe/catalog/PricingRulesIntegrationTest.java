package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PricingRulesIntegrationTest
 * Purpose: Pins the order of rules the single catalog pricing path enforces for a till line.
 */
class PricingRulesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModifierRepository modifiers;

    private String token;
    private String branchId;

    @BeforeEach
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String newProduct(String s) throws Exception {
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Cat-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        return JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Tea-%s\",\"sku\":\"TEA-%s\",\"basePrice\":25000}"
                        .formatted(categoryId, s, s)), "$.id");
    }

    /** Creates a group with the given bounds holding one +5000 modifier; returns [groupId, modifierId]. */
    private String[] newGroup(String s, int min, int max) throws Exception {
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Grp-%s\",\"minSelect\":%d,\"maxSelect\":%d}".formatted(s, min, max)), "$.id");
        String modifierId = JsonPath.read(create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"Mod-%s\",\"priceDelta\":5000,\"displayOrder\":1}".formatted(s)), "$.id");
        return new String[] { groupId, modifierId };
    }

    private void attach(String productId, String... groupIds) throws Exception {
        send(put("/api/v1/products/" + productId + "/modifier-groups"),
                "{\"modifierGroupIds\":[%s]}".formatted(
                        String.join(",", java.util.Arrays.stream(groupIds).map(g -> "\"" + g + "\"").toList())))
                .andExpect(status().isOk());
    }

    private ResultActions order(String productId, String... modifierIds) throws Exception {
        return send(post("/api/v1/orders"), ("{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\","
                + "\"quantity\":1,\"modifierIds\":[%s]}]}").formatted(productId,
                String.join(",", java.util.Arrays.stream(modifierIds).map(m -> "\"" + m + "\"").toList())));
    }

    private void setAvailability(String productId, boolean available, String override) throws Exception {
        send(put("/api/v1/products/" + productId + "/availability"),
                "{\"branchId\":\"%s\",\"available\":%s,\"priceOverride\":%s}".formatted(branchId, available, override))
                .andExpect(status().isOk());
    }

    private BigDecimal price(String productId, String query) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/products/" + productId + "/price" + query)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.price.amount")));
    }

    @Test
    void inactiveProduct_isRefusedAtTheTill() throws Exception {
        String productId = newProduct(uniq());
        send(put("/api/v1/products/" + productId),
                "{\"categoryId\":\"%s\",\"name\":\"Off\",\"sku\":\"OFF-%s\",\"basePrice\":25000,\"active\":false}"
                        .formatted(JsonPath.read(mockMvc.perform(authed(get("/api/v1/products/" + productId)))
                                .andReturn().getResponse().getContentAsString(), "$.categoryId"), uniq()))
                .andExpect(status().isOk());

        order(productId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void productUnavailableAtBranch_isRefusedAtTheTill() throws Exception {
        String productId = newProduct(uniq());
        setAvailability(productId, false, "null");

        order(productId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void branchOverride_priceTheLineAndTheEndpointAlike() throws Exception {
        String productId = newProduct(uniq());
        setAvailability(productId, true, "28000");

        String json = order(productId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        BigDecimal lineTotal = new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.lines[0].lineTotal.amount")));
        assertThat(lineTotal).isEqualByComparingTo("28000");
        assertThat(price(productId, "?branchId=" + branchId)).isEqualByComparingTo("28000");
    }

    @Test
    void modifierFromUnattachedGroup_isRefused() throws Exception {
        String s = uniq();
        String productId = newProduct(s);
        String[] other = newGroup(s, 0, 1);

        order(productId, other[1]).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void exceedingGroupMax_isRefused() throws Exception {
        String s = uniq();
        String productId = newProduct(s);
        String[] group = newGroup(s, 0, 1);
        String second = JsonPath.read(create("/api/v1/modifier-groups/" + group[0] + "/modifiers",
                "{\"name\":\"Two-%s\",\"priceDelta\":1000,\"displayOrder\":2}".formatted(s)), "$.id");
        attach(productId, group[0]);

        order(productId, group[1], second).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void missingRequiredGroup_isRefused() throws Exception {
        String s = uniq();
        String productId = newProduct(s);
        String[] group = newGroup(s, 1, 1);
        attach(productId, group[0]);

        order(productId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void inactiveModifier_isRefused() throws Exception {
        String s = uniq();
        String productId = newProduct(s);
        String[] group = newGroup(s, 0, 1);
        attach(productId, group[0]);
        var modifier = modifiers.findById(UUID.fromString(group[1])).orElseThrow();
        modifier.setActive(false);
        modifiers.save(modifier);

        order(productId, group[1]).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void priceEndpoint_matchesTheOrderLineWithModifiers() throws Exception {
        String s = uniq();
        String productId = newProduct(s);
        String[] group = newGroup(s, 1, 1);
        attach(productId, group[0]);
        setAvailability(productId, true, "28000");

        String json = order(productId, group[1]).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        BigDecimal line = new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.lines[0].lineTotal.amount")));
        assertThat(line).isEqualByComparingTo("33000");
        assertThat(price(productId, "?branchId=" + branchId + "&modifierIds=" + group[1])).isEqualByComparingTo(line);
    }
}
