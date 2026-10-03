package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: StockCheckAgreementTest
 * Purpose: Proves the price check, the menu and the payment call the same lines short, so they can never disagree.
 */
class StockCheckAgreementTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryApi inventoryApi;

    private String token;
    private UUID branchId;
    private UUID companyId;

    @BeforeEach
    void signIn() throws Exception {
        token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = UUID.fromString(((List<String>) JsonPath.read(me, "$.branchIds")).get(0));
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** A fresh ingredient measured in grams, at the given on-hand; {@code null} leaves it untracked. */
    private UUID gramsIngredient(String onHand) throws Exception {
        UUID id = UUID.fromString(JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Agree-%s\",\"baseUnit\":\"g\"}".formatted(UUID.randomUUID().toString().substring(0, 8))),
                "$.id"));
        if (onHand != null) {
            create("/api/v1/stock/adjust", "{\"ingredientId\":\"%s\",\"quantityDelta\":%s}".formatted(id, onHand));
        }
        return id;
    }

    private record Case(String name, String onHand, boolean short_, String... lines) {
    }

    @Test
    void priceCheckMenuAndPayment_callTheSameLinesShort() throws Exception {
        List<Case> cases = List.of(
                new Case("fits", "18", false, "18 g"),
                new Case("exactly enough, merged across units", "1000", false, "0.5 kg", "500 g"),
                new Case("one gram over, merged across units", "1000", true, "0.5 kg", "501 g"),
                new Case("never stocked", null, true, "1 g"),
                new Case("never stocked, nets to zero", null, false, "1 kg", "-1000 g"),
                new Case("in deficit, nets to zero", "-50", false, "10 g", "-10 g"),
                new Case("in deficit, gives stock back", "-50", false, "-10 g"),
                new Case("in deficit, takes some", "-50", true, "1 g"));

        for (Case c : cases) {
            UUID ingredient = gramsIngredient(c.onHand());
            List<StockLine> lines = Arrays.stream(c.lines())
                    .map(l -> new StockLine(ingredient, new BigDecimal(l.split(" ")[0]), l.split(" ")[1]))
                    .toList();
            UUID key = UUID.randomUUID();

            boolean quoteShort = !TenantContext.runAs(companyId, () -> inventoryApi.shortfalls(branchId, lines)).isEmpty();
            boolean menuShort = TenantContext.runAs(companyId,
                    () -> inventoryApi.unavailable(branchId, Map.of(key, lines))).contains(key);
            boolean paymentRefused = refused(lines);

            assertThat(List.of(quoteShort, menuShort, paymentRefused)).as(c.name())
                    .containsExactly(c.short_(), c.short_(), c.short_());
        }
    }

    /** Whether the payment's deduction refuses the lines for want of stock; a success commits it. */
    private boolean refused(List<StockLine> lines) {
        try {
            TenantContext.runAs(companyId, () -> inventoryApi.deductForOrder(branchId, lines, "ORDER", UUID.randomUUID()));
            return false;
        } catch (BusinessRuleException e) {
            assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
            return true;
        }
    }

    @Test
    void unknownIngredient_failsThePriceCheckLikeThePayment_butOnlyMarksItsMenuEntryUnavailable() throws Exception {
        UUID stocked = gramsIngredient("100");
        List<StockLine> unknown = List.of(new StockLine(UUID.randomUUID(), BigDecimal.ONE, "g"));
        List<StockLine> fine = List.of(new StockLine(stocked, BigDecimal.TEN, "g"));
        UUID broken = UUID.randomUUID();
        UUID sellable = UUID.randomUUID();

        assertThatThrownBy(() -> TenantContext.runAs(companyId, () -> inventoryApi.shortfalls(branchId, unknown)))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Ingredient not found");
        assertThatThrownBy(() -> TenantContext.runAs(companyId,
                        () -> inventoryApi.deductForOrder(branchId, unknown, "ORDER", UUID.randomUUID())))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Ingredient not found");
        // one product the ledger cannot convert must not take the whole menu down
        assertThat(TenantContext.runAs(companyId,
                () -> inventoryApi.unavailable(branchId, Map.of(broken, unknown, sellable, fine))))
                .containsExactly(broken);
    }
}
