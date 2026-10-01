package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: UnitConversionSeamTest
 * Purpose: Proves manual adjustments and waste convert their unit through the ledger like every other stock path.
 */
class UnitConversionSeamTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;

    @BeforeEach
    void signIn() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String ingredient(String baseUnit) throws Exception {
        String json = mockMvc.perform(authed(post("/api/v1/ingredients"))
                        .content("{\"name\":\"Seam%d\",\"baseUnit\":\"%s\"}".formatted(SEQ.incrementAndGet(), baseUnit)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private BigDecimal onHand(String ingredientId) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock"))).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return vals.isEmpty() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(vals.get(0)));
    }

    @Test
    void adjust_inKilogramsOnAGramIngredientMovesGrams() throws Exception {
        String id = ingredient("g");

        mockMvc.perform(authed(post("/api/v1/stock/adjust"))
                        .content("{\"ingredientId\":\"%s\",\"quantityDelta\":2,\"unit\":\"kg\"}".formatted(id)))
                .andExpect(status().isOk());

        assertThat(onHand(id)).isEqualByComparingTo("2000");
    }

    @Test
    void waste_inLitresOnAMillilitreIngredientMovesMillilitres() throws Exception {
        String id = ingredient("ml");

        mockMvc.perform(authed(post("/api/v1/stock/waste"))
                        .content("{\"ingredientId\":\"%s\",\"quantity\":0.5,\"unit\":\"l\"}".formatted(id)))
                .andExpect(status().isOk());

        assertThat(onHand(id)).isEqualByComparingTo("-500");
    }

    @Test
    void adjust_withoutAUnitStaysInTheBaseUnit() throws Exception {
        String id = ingredient("g");

        mockMvc.perform(authed(post("/api/v1/stock/adjust"))
                        .content("{\"ingredientId\":\"%s\",\"quantityDelta\":150}".formatted(id)))
                .andExpect(status().isOk());

        assertThat(onHand(id)).isEqualByComparingTo("150");
    }

    @Test
    void adjust_inAnotherDimensionIsRefusedAndChangesNothing() throws Exception {
        String id = ingredient("ml");

        mockMvc.perform(authed(post("/api/v1/stock/adjust"))
                        .content("{\"ingredientId\":\"%s\",\"quantityDelta\":5,\"unit\":\"g\"}".formatted(id)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(onHand(id)).isEqualByComparingTo("0");
    }
}
