package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.events.DomainEventPublisher;
import vn.essvn.erpcafe.sales.api.OrderReversed;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;

/**
 * Class: ReportProjectionTest
 * Purpose: Proves the daily branch figures follow the sale and shift events, once per event, on the report day each happened.
 */
class ReportProjectionTest extends ReportFixtures {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant SALE_DAY = Instant.parse("2026-03-02T05:00:00Z");
    /** gross 50,000, discount 5,000, net 45,000, tax 4,500 at 10%, grand 49,500; COGS 2 x 50 g x 100 = 10,000. */
    private static final String TWO_DRINKS = "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\","
            + "\"quantity\":2,\"lineDiscount\":5000}]}";

    @Autowired private DomainEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;

    private static LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, VIETNAM);
    }

    private UUID branch() {
        return UUID.fromString(branchIds.get(0));
    }

    private UUID company() {
        return jdbc.queryForObject("SELECT company_id FROM branch WHERE id = ?", UUID.class, branch());
    }

    private Instant orderTime(String column, String orderId) {
        return jdbc.queryForObject("SELECT " + column + " FROM sales_order WHERE id = ?::uuid", Timestamp.class, orderId)
                .toInstant();
    }

    /** The test branch's figures for a day, read past row-level security; empty when the day has no row. */
    private Map<String, Object> figures(LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_count, gross_sales, discount_total, net_sales,"
                + " tax_total, cogs, labor_cost FROM report_daily_branch WHERE branch_id = ? AND day = ?", branch(), day);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** The branch's days that hold any figure; a day whose figures all moved elsewhere keeps an empty row. */
    private List<Map<String, Object>> bookedDays() {
        return jdbc.queryForList("SELECT * FROM report_daily_branch WHERE branch_id = ? AND (order_count <> 0"
                + " OR gross_sales <> 0 OR discount_total <> 0 OR net_sales <> 0 OR tax_total <> 0 OR cogs <> 0"
                + " OR labor_cost <> 0) ORDER BY day", branch());
    }

    private static void assertSales(Map<String, Object> row, long orders, String gross, String discount, String net,
            String tax, String cogs) {
        assertThat(((Number) row.get("order_count")).longValue()).isEqualTo(orders);
        assertThat((BigDecimal) row.get("gross_sales")).isEqualByComparingTo(gross);
        assertThat((BigDecimal) row.get("discount_total")).isEqualByComparingTo(discount);
        assertThat((BigDecimal) row.get("net_sales")).isEqualByComparingTo(net);
        assertThat((BigDecimal) row.get("tax_total")).isEqualByComparingTo(tax);
        assertThat((BigDecimal) row.get("cogs")).isEqualByComparingTo(cogs);
    }

    private static BigDecimal labor(Map<String, Object> row) {
        return (BigDecimal) row.get("labor_cost");
    }

    @Test
    void aCompletedOrder_isBookedOnItsCompletionDay_onceTheDispatcherRuns() throws Exception {
        freshTenant(1, "0.10");
        String orderId = paidOrder(TWO_DRINKS.formatted(sellableProduct()), 49500);
        LocalDate day = dayOf(orderTime("completed_at", orderId));
        assertThat(figures(day)).as("nothing is booked before the event is delivered").isEmpty();

        dispatcher.dispatchPending();

        assertSales(figures(day), 1, "50000", "5000", "45000", "4500", "10000");
        assertThat(labor(figures(day))).isZero();
        // the backfill, recomputing from the orders and the ledger, arrives at the same row
        List<Map<String, Object>> fed = bookedDays();
        rebuildFigures();
        assertThat(bookedDays()).isEqualTo(fed);
    }

    @Test
    void theSameEventDeliveredTwice_changesNothing() throws Exception {
        freshTenant(1, "0.10");
        String orderId = paidOrder(TWO_DRINKS.formatted(sellableProduct()), 49500);
        dispatcher.dispatchPending();
        LocalDate day = dayOf(orderTime("completed_at", orderId));
        Map<String, Object> once = figures(day);

        // delivered again, as an at-least-once transport may: the projection has applied it already
        jdbc.update("UPDATE domain_event SET processed_at = NULL WHERE payload->>'orderId' = ?", orderId);
        dispatcher.dispatchPending();

        assertThat(figures(day)).isEqualTo(once);
        assertSales(once, 1, "50000", "5000", "45000", "4500", "10000");
    }

    @Test
    void aRefundEventOnALaterDay_writesNegativesOnThatDay() throws Exception {
        freshTenant(1, "0.10");
        String orderId = paidOrder(TWO_DRINKS.formatted(sellableProduct()), 49500);
        dispatcher.dispatchPending();
        Instant completedAt = orderTime("completed_at", orderId);
        Instant refundedAt = completedAt.plus(3, ChronoUnit.DAYS);
        UUID company = company();

        TenantContext.runAs(company, () -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                publisher.publish(new OrderReversed(UUID.fromString(orderId), company, branch(), refundedAt,
                        new BigDecimal("50000"), new BigDecimal("5000"), new BigDecimal("45000"),
                        new BigDecimal("4500"), new BigDecimal("10000")))));
        dispatcher.dispatchPending();

        assertSales(figures(dayOf(completedAt)), 1, "50000", "5000", "45000", "4500", "10000");
        assertSales(figures(dayOf(refundedAt)), 0, "-50000", "-5000", "-45000", "-4500", "-10000");
    }

    @Test
    void anAdjustedShift_movesItsLabourToTheDayItNowEnds() throws Exception {
        freshTenant(1, "0");
        Instant now = Instant.now();
        UUID shiftId = TenantContext.runAs(company(), () -> {
            Employee barista = employeeRepository.save(
                    new Employee("Rpt Barista " + uniq(), "Barista", Money.of(new BigDecimal("36000"))));
            Shift shift = new Shift(branch(), barista.getId(), now.minus(3, ChronoUnit.HOURS),
                    now.plus(5, ChronoUnit.HOURS), null);
            shift.clockIn(now.minus(2, ChronoUnit.HOURS), Money.of(new BigDecimal("36000")));
            return shiftRepository.save(shift).getId();
        });
        String admin = adminToken();
        mockMvc.perform(post("/api/v1/staff/shifts/" + shiftId + "/clock-out")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)).header("X-Branch-Id", branch()))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();
        Shift clockedOut = TenantContext.runAsSystem(() -> shiftRepository.findById(shiftId).orElseThrow());
        LocalDate firstDay = dayOf(clockedOut.getClockOutAt());
        assertThat(labor(figures(firstDay))).isEqualByComparingTo(clockedOut.getLaborCost().getAmount());

        // a manager corrects the punch to four hours three days ago: 4 h x 36,000
        Instant in = now.minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant out = in.plus(4, ChronoUnit.HOURS);
        mockMvc.perform(post("/api/v1/staff/shifts/" + shiftId + "/adjust")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)).header("X-Branch-Id", branch())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clockIn\":\"%s\",\"clockOut\":\"%s\"}".formatted(in, out)))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();

        assertThat(labor(figures(firstDay))).isZero();
        assertThat(labor(figures(dayOf(out)))).isEqualByComparingTo("144000");
        List<Map<String, Object>> fed = bookedDays();
        rebuildFigures();
        assertThat(bookedDays()).isEqualTo(fed);
    }

    @Test
    void aSaleThatReturnsAnIngredient_isCostedWithTheLedgersOwnSign_byTheEventsAndTheRebuildAlike() throws Exception {
        freshTenant(1, "0");
        String productId = sellableProduct();
        String beanId = jdbc.queryForObject("SELECT l.ingredient_id::text FROM recipe_line l"
                + " JOIN recipe r ON r.id = l.recipe_id WHERE r.product_id = ?::uuid", String.class, productId);
        // the drink's recipe takes 50 g; this modifier takes 60 g off it, so a sale returns 10 g to stock
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Strength-%s\",\"minSelect\":0,\"maxSelect\":1}".formatted(uniq())), "$.id");
        String lighterId = JsonPath.read(create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                ("{\"name\":\"Lighter\",\"priceDelta\":0,\"displayOrder\":1,"
                        + "\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":-60,\"unit\":\"g\"}]}")
                        .formatted(beanId)), "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/modifier-groups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId)))
                .andExpect(status().isOk());
        String orderId = paidOrder(("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,"
                + "\"modifierIds\":[\"%s\"]}]}").formatted(productId, lighterId), 25000);
        dispatcher.dispatchPending();

        // 10 g back at 100 each: the sale's cost is -1,000, as the order recorded it
        assertSales(figures(dayOf(orderTime("completed_at", orderId))), 1, "25000", "0", "25000", "0", "-1000");
        List<Map<String, Object>> fed = bookedDays();
        rebuildFigures();
        assertThat(bookedDays()).isEqualTo(fed);
    }

    @Test
    void aLegacyPaidOrderCompletedLate_movesFromItsCreationDayToItsCompletionDay() throws Exception {
        freshTenant(1, "0");
        String orderId = paidOrder("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}"
                .formatted(sellableProduct()), 25000);
        dispatcher.dispatchPending();
        // a pre-migration row: paid, never completed, nothing taken from stock; the backfill booked it when created
        jdbc.update("DELETE FROM stock_movement WHERE ref_id = ?::uuid", orderId);
        jdbc.update("DELETE FROM ledger_reference WHERE ref_id = ?::uuid", orderId);
        jdbc.update("UPDATE sales_order SET status = 'PAID', completed_at = NULL, cogs_total = NULL,"
                + " created_at = ?::timestamptz WHERE id = ?::uuid", SALE_DAY.toString(), orderId);
        rebuildFigures();
        assertSales(figures(dayOf(SALE_DAY)), 1, "25000", "0", "25000", "0", "0");

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/complete"))).andExpect(status().isOk());
        dispatcher.dispatchPending();

        LocalDate completionDay = dayOf(orderTime("completed_at", orderId));
        assertSales(figures(dayOf(SALE_DAY)), 0, "0", "0", "0", "0", "0");
        assertSales(figures(completionDay), 1, "25000", "0", "25000", "0", "5000");
        List<Map<String, Object>> fed = bookedDays();
        rebuildFigures();
        assertThat(bookedDays()).isEqualTo(fed);
    }
}
