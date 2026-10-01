package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Class: ReportPeriodBasisTest
 * Purpose: Proves a sale, its cost and its refund are each booked in the period where they happened.
 */
class ReportPeriodBasisTest extends ReportFixtures {

    private static final Instant SALE_DAY = Instant.parse("2026-03-02T05:00:00Z");
    private static final Instant REFUND_DAY = Instant.parse("2026-03-09T05:00:00Z");

    private void stamp(String orderId, String column, Instant at) {
        jdbc.update("update sales_order set " + column + " = ?::timestamptz where id = ?::uuid", at.toString(), orderId);
    }

    /** Moves the order's ledger movements of the given type to the stated instant. */
    private void stampMovements(String orderId, String type, Instant at) {
        jdbc.update("update stock_movement set created_at = ?::timestamptz where type = ? and ref_id = ?::uuid",
                at.toString(), type, orderId);
    }

    private void assertStampedBetween(String orderId, String column, Instant before) {
        Instant at = jdbc.queryForObject("select " + column + " from sales_order where id = ?::uuid",
                Timestamp.class, orderId).toInstant();
        assertThat(at).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    private void assertMargin(String json, String net, String cogs) {
        assertThat(num(json, "$.netSales")).isEqualByComparingTo(net);
        assertThat(num(json, "$.cogs")).isEqualByComparingTo(cogs);
        assertThat(num(json, "$.grossMargin")).isEqualByComparingTo(new BigDecimal(net).subtract(new BigDecimal(cogs)));
    }

    @Test
    void refundOnALaterDay_isBookedThereAsNegativeSalesAndNegativeCogs() throws Exception {
        freshTenant(1, "0.10");
        String productId = sellableProduct();
        Instant before = Instant.now();
        // gross 50,000, discount 5,000, net 45,000, tax 4,500, grand 49,500; COGS 2 x 50 g x 100 = 10,000.
        String orderId = paidOrder(("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\","
                + "\"quantity\":2,\"lineDiscount\":5000}]}").formatted(productId), 49500);
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/refund"))).andExpect(status().isOk());

        assertStampedBetween(orderId, "completed_at", before);
        assertStampedBetween(orderId, "reversed_at", before);
        stamp(orderId, "completed_at", SALE_DAY);
        stamp(orderId, "reversed_at", REFUND_DAY);
        stampMovements(orderId, "SALE_DEPLETION", SALE_DAY);
        jdbc.update("update stock_movement set created_at = ?::timestamptz where type = 'SALE_REVERSAL'"
                + " and branch_id = ?::uuid", REFUND_DAY.toString(), branchIds.get(0));

        String week1 = report("summary", Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-09T00:00:00Z"));
        String week2 = report("summary", Instant.parse("2026-03-09T00:00:00Z"), Instant.parse("2026-03-16T00:00:00Z"));

        assertMargin(week1, "45000", "10000");
        assertThat(num(week1, "$.orderCount")).isEqualByComparingTo("1");
        assertMargin(week2, "-45000", "-10000");

        String days = report("sales-by-day", Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-16T00:00:00Z"));
        assertThat((List<String>) JsonPath.read(days, "$[*].day")).containsExactly("2026-03-02", "2026-03-09");
        assertThat(num(days, "$[0].netSales")).isEqualByComparingTo("45000");
        assertThat(num(days, "$[1].netSales")).isEqualByComparingTo("-45000");
    }

    @Test
    void legacyPaidOrder_countsAtCreationAndItsVoidSubtractsWhenVoided() throws Exception {
        freshTenant(1, "0");
        String productId = sellableProduct();
        Instant before = Instant.now();
        String orderId = paidOrder("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}"
                .formatted(productId), 25000);
        // Make it look like a pre-migration row: PAID, never completed, no timestamps.
        jdbc.update("update sales_order set status = 'PAID', completed_at = null, reversed_at = null,"
                + " created_at = ?::timestamptz where id = ?::uuid", SALE_DAY.toString(), orderId);

        String week1 = report("summary", Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(num(week1, "$.netSales")).isEqualByComparingTo("25000");

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/void"))).andExpect(status().isOk());
        assertStampedBetween(orderId, "reversed_at", before);
        stamp(orderId, "reversed_at", REFUND_DAY);

        String week1After = report("summary", Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-09T00:00:00Z"));
        String week2 = report("summary", Instant.parse("2026-03-09T00:00:00Z"), Instant.parse("2026-03-16T00:00:00Z"));
        assertThat(num(week1After, "$.netSales")).isEqualByComparingTo("25000");
        assertThat(num(week2, "$.netSales")).isEqualByComparingTo("-25000");
    }
}
