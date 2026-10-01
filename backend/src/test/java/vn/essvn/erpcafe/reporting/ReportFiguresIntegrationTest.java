package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Class: ReportFiguresIntegrationTest
 * Purpose: Proves report figures add up (net sales, margins, Vietnam-time days, period limits, caller-only outlets).
 */
class ReportFiguresIntegrationTest extends ReportFixtures {

    @Test
    void summary_marginsAreComputedOnNetSalesAndOneCogsFigure() throws Exception {
        freshTenant(1, "0.10");
        String productId = sellableProduct();
        paidOrder(("{\"orderType\":\"DINE_IN\",\"orderDiscount\":3000,"
                + "\"lines\":[{\"productId\":\"%s\",\"quantity\":2,\"lineDiscount\":5000}]}").formatted(productId), 46200);

        Instant now = Instant.now();
        String json = report("summary", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        assertThat(num(json, "$.grossSales")).isEqualByComparingTo("50000");
        assertThat(num(json, "$.discountTotal")).isEqualByComparingTo("8000");
        assertThat(num(json, "$.netSales")).isEqualByComparingTo("42000");
        assertThat(num(json, "$.taxTotal")).isEqualByComparingTo("4200");
        assertThat(num(json, "$.grandTotal")).isEqualByComparingTo("46200");
        assertThat(num(json, "$.cogs")).isEqualByComparingTo("10000");
        assertThat(num(json, "$.grossMargin")).isEqualByComparingTo("32000");
        assertThat(num(json, "$.netMargin")).isEqualByComparingTo("32000");
        mockMvc.perform(authed(get("/api/v1/reports/summary")
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plus(1, ChronoUnit.HOURS).toString())))
                .andExpect(jsonPath("$.cogsOrders").doesNotExist())
                .andExpect(jsonPath("$.revenue").doesNotExist());
    }

    @Test
    void salesByDay_groupsInVietnamTime() throws Exception {
        freshTenant(1, "0.10");
        String productId = sellableProduct();
        // gross 25,000, discount 5,000, net 20,000, tax 2,000, grand 22,000: the day figure must be the net.
        String orderId = paidOrder(
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,\"lineDiscount\":5000}]}"
                        .formatted(productId), 22000);
        // 17:30 UTC on 2 March is 00:30 on 3 March in Vietnam.
        jdbc.update("update sales_order set completed_at = ?::timestamptz where id = ?::uuid",
                "2026-03-02T17:30:00Z", orderId);

        String json = report("sales-by-day", Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-05T00:00:00Z"));

        List<String> days = JsonPath.read(json, "$[*].day");
        assertThat(days).containsExactly("2026-03-03");
        assertThat(num(json, "$[0].netSales")).isEqualByComparingTo("20000");
    }

    @Test
    void fromAfterTo_isRejectedWith400() throws Exception {
        freshTenant(1, "0");
        Instant now = Instant.now();
        mockMvc.perform(authed(get("/api/v1/reports/summary")
                        .param("from", now.toString()).param("to", now.minus(1, ChronoUnit.DAYS).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rangeLongerThanTheLimit_isRejectedWith400() throws Exception {
        freshTenant(1, "0");
        Instant now = Instant.now();
        mockMvc.perform(authed(get("/api/v1/reports/sales-by-day")
                        .param("from", now.minus(400, ChronoUnit.DAYS).toString()).param("to", now.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void outlets_listsOnlyTheBranchesTheCallerMayAccess() throws Exception {
        freshTenant(2, "0");
        Instant now = Instant.now();
        String json = report("outlets", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        List<String> ids = JsonPath.read(json, "$[*].branchId");
        assertThat(ids).containsExactly(branchIds.get(0));
    }
}
