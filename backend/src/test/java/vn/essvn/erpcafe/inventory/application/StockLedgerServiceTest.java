package vn.essvn.erpcafe.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * The ledger's unit discipline (plan task A1): every quantity that reaches the
 * ledger is converted to the ingredient's base unit before lines are merged, so
 * a recipe line in kg and a modifier delta in g deduct the right amount instead
 * of being summed as raw numbers. Cross-dimension lines (g + ml) are a bug in
 * the caller and are rejected, never guessed.
 */
class StockLedgerServiceTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    private StockMovementRepository movementRepository;

    private UUID branchId;

    /** Creates an ingredient with base unit {@code g} through the API; returns its id. */
    private UUID seedGramIngredient() throws Exception {
        String token = adminToken();
        branchId = UUID.fromString(((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0));
        String json = mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"LedgerBeans%d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    @Test
    void deduct_convertsEachLineToTheBaseUnitBeforeMerging() throws Exception {
        UUID ingredientId = seedGramIngredient();
        UUID refId = UUID.randomUUID();

        // 500 g + 1 kg of the same ingredient: must merge to 1500 g, not 501 of anything.
        ledger.deduct(branchId, List.of(
                new StockLine(ingredientId, new BigDecimal("500"), "g"),
                new StockLine(ingredientId, new BigDecimal("1"), "kg")),
                "TEST_DEDUCT", refId);

        List<StockMovement> movements = movementRepository.findByRefTypeAndRefId("TEST_DEDUCT", refId);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("-1500");
    }

    @Test
    void deduct_rejectsLinesOfDifferentDimensions() throws Exception {
        UUID ingredientId = seedGramIngredient();

        // g (mass) + ml (volume) for one ingredient is caller error: reject, don't guess.
        assertThatThrownBy(() -> ledger.deduct(branchId, List.of(
                new StockLine(ingredientId, new BigDecimal("100"), "g"),
                new StockLine(ingredientId, new BigDecimal("100"), "ml")),
                "TEST_DEDUCT", UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
