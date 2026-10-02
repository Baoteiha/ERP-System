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
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
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
    private UUID companyId;

    /** Creates an ingredient with base unit {@code g} through the API; returns its id. */
    private UUID seedGramIngredient() throws Exception {
        String token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = UUID.fromString(((List<String>) JsonPath.read(me, "$.branchIds")).get(0));
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
        String json = mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"LedgerBeans%d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    /** Calls the ledger as the branch's company, as a request at that branch would. */
    private <T> T asCompany(Supplier<T> work) {
        return TenantContext.runAs(companyId, work);
    }

    @Test
    void deduct_convertsEachLineToTheBaseUnitBeforeMerging() throws Exception {
        UUID ingredientId = seedGramIngredient();
        UUID refId = UUID.randomUUID();

        // 500 g + 1 kg of the same ingredient: must merge to 1500 g, not 501 of anything.
        asCompany(() -> ledger.deduct(branchId, List.of(
                new StockLine(ingredientId, new BigDecimal("500"), "g"),
                new StockLine(ingredientId, new BigDecimal("1"), "kg")),
                "TEST_DEDUCT", refId));

        List<StockMovement> movements = asCompany(() -> movementRepository.findByRefTypeAndRefId("TEST_DEDUCT", refId));
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("-1500");
    }

    @Test
    void deduct_rejectsLinesOfDifferentDimensions() throws Exception {
        UUID ingredientId = seedGramIngredient();

        // g (mass) + ml (volume) for one ingredient is caller error: reject, don't guess.
        assertThatThrownBy(() -> asCompany(() -> ledger.deduct(branchId, List.of(
                new StockLine(ingredientId, new BigDecimal("100"), "g"),
                new StockLine(ingredientId, new BigDecimal("100"), "ml")),
                "TEST_DEDUCT", UUID.randomUUID())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void deduct_repeatReturnsTheSameCostWhenAModifierMadeOneLineNetNegative() throws Exception {
        UUID beans = seedGramIngredient();
        UUID syrup = seedGramIngredient();
        asCompany(() -> ledger.receive(branchId, beans, new BigDecimal("1000"), null,
                Money.of(new BigDecimal("0.20")), "TEST_PO", null));
        asCompany(() -> ledger.receive(branchId, syrup, new BigDecimal("1000"), null,
                Money.of(new BigDecimal("0.10")), "TEST_PO", null));
        UUID refId = UUID.randomUUID();
        // 100 g beans taken, 50 g syrup given back (a modifier removing more than the recipe uses):
        // 100 x 0.20 - 50 x 0.10 = 15.
        List<StockLine> lines = List.of(
                new StockLine(beans, new BigDecimal("100"), "g"),
                new StockLine(syrup, new BigDecimal("-50"), "g"));

        Money first = asCompany(() -> ledger.deduct(branchId, lines, "TEST_DEDUCT", refId)).totalCost();
        Money repeat = asCompany(() -> ledger.deduct(branchId, lines, "TEST_DEDUCT", refId)).totalCost();

        assertThat(first.getAmount()).isEqualByComparingTo("15");
        assertThat(repeat.getAmount()).isEqualByComparingTo(first.getAmount());
    }

    @Test
    void toBase_roundsAConversionToTheLedgersFourDecimalsHalfEven() throws Exception {
        UUID ingredientId = seedGramIngredient();

        // 0.25 mg is 0.00025 g: a tie at four decimals, which HALF_EVEN settles on the even 0.0002.
        BigDecimal tie = asCompany(() -> ledger.toBase(branchId, ingredientId, new BigDecimal("0.25"), "mg"));
        BigDecimal above = asCompany(() -> ledger.toBase(branchId, ingredientId, new BigDecimal("0.35"), "mg"));

        assertThat(tie).isEqualTo(new BigDecimal("0.0002"));
        assertThat(above).isEqualTo(new BigDecimal("0.0004"));
    }

    @Test
    void deduct_roundsTheMergedSumOnce_soTinyLinesStillAddUp() throws Exception {
        UUID ingredientId = seedGramIngredient();
        UUID refId = UUID.randomUUID();

        // 0.03 mg is 0.00003 g: each line alone rounds to 0.0000, together they are 0.00006 g, i.e. 0.0001.
        asCompany(() -> ledger.deduct(branchId, List.of(
                new StockLine(ingredientId, new BigDecimal("0.03"), "mg"),
                new StockLine(ingredientId, new BigDecimal("0.03"), "mg")),
                "TEST_DEDUCT", refId));

        List<StockMovement> movements = asCompany(() -> movementRepository.findByRefTypeAndRefId("TEST_DEDUCT", refId));
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("-0.0001");
    }
}
