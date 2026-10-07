package vn.essvn.erpcafe.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.events.DomainEventDispatcher;
import vn.essvn.erpcafe.reporting.application.ReportingProperties;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ReportFixtures
 * Purpose: Gives report tests a fresh tenant with stock, a priced drink and paid orders to report on.
 */
abstract class ReportFixtures extends AbstractIntegrationTest {

    /** The schema owner's SQL: fixtures backdate and remove rows past row-level security. */
    protected JdbcTemplate jdbc;

    /** Delivers the sale and shift events that feed the report figures; the test profile has no schedule. */
    @Autowired
    protected DomainEventDispatcher dispatcher;

    @Autowired
    private ReportingProperties reportingProperties;

    protected String token;
    protected List<String> branchIds;

    @Autowired
    void ownerSql(@Qualifier("ownerDataSource") DataSource ownerDataSource) {
        jdbc = new JdbcTemplate(ownerDataSource);
    }

    @AfterEach
    void removeFreshBranches() {
        // straight SQL: the branches hold orders and stock, which the delete endpoint refuses
        if (branchIds != null) {
            for (String id : branchIds) {
                jdbc.update("DELETE FROM user_branch_access WHERE branch_id = ?::uuid", id);
                jdbc.update("UPDATE branch SET deleted = true WHERE id = ?::uuid", id);
            }
        }
    }

    protected static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected String adminPost(String url, String body) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * A user who only has the given fresh branches, so every figure over them comes from this test's orders
     * alone. The admin is made OWNER of each branch when it is created; {@link #removeFreshBranches} undoes
     * that, so the admin's own branches are unchanged once the test is over.
     */
    protected void freshTenant(int branchCount, String taxRate) throws Exception {
        String s = uniq();
        branchIds = new ArrayList<>();
        for (int i = 0; i < branchCount; i++) {
            branchIds.add(JsonPath.read(adminPost("/api/v1/branches",
                    "{\"name\":\"Rpt %s-%d\",\"code\":\"R%s%d\",\"taxRate\":%s}".formatted(s, i, s, i, taxRate)), "$.id"));
        }
        String roleId = JsonPath.read(adminPost("/api/v1/roles",
                ("{\"name\":\"Rpt %s\",\"description\":\"d\",\"permissions\":[\"catalog:read\",\"catalog:write\","
                        + "\"inventory:read\",\"inventory:write\",\"purchasing:read\",\"purchasing:write\","
                        + "\"sales:read\",\"sales:write\",\"sales:refund\",\"report:read\"]}").formatted(s)), "$.id");
        String userId = JsonPath.read(adminPost("/api/v1/users",
                "{\"email\":\"rpt%s@a.test\",\"password\":\"password123\",\"fullName\":\"Rpt\"}".formatted(s)), "$.id");
        // Only the first branch is granted: the outlet test relies on the second one being out of reach.
        adminPost("/api/v1/users/" + userId + "/access",
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchIds.get(0), roleId));
        token = accessToken("rpt%s@a.test".formatted(s), "password123");
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchIds.get(0));
    }

    protected String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** Stock of 1000 g costing 100 each, and a 25,000 drink whose recipe uses 50 g. Returns the product id. */
    protected String sellableProduct() throws Exception {
        String s = uniq();
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Bean-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster-%s\"}".formatted(s)), "$.id");
        String itemId = itemFor(token, branchIds.get(0), supplierId, ingredientId);
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":100}]}"
                        .formatted(supplierId, itemId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Latte-%s\",\"sku\":\"LAT-%s\",\"basePrice\":25000}"
                        .formatted(categoryId, s, s)), "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":50,\"unit\":\"g\"}]}"
                                .formatted(ingredientId)))
                .andExpect(status().isOk());
        return productId;
    }

    /** Creates and fully pays an order; returns its id. */
    protected String paidOrder(String body, int amount) throws Exception {
        String orderId = JsonPath.read(create("/api/v1/orders", body), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":%d}".formatted(amount)))
                .andExpect(status().isOk());
        return orderId;
    }

    protected BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /**
     * Recomputes the first branch's figures from its orders, stock ledger and shifts, as the V23 backfill
     * does. A test that moves a row's timestamps after the fact needs it: no event announces that.
     */
    protected void rebuildFigures() {
        jdbc.queryForList("SELECT report_rebuild_branch(?::uuid, ?)", branchIds.get(0),
                reportingProperties.zone().getId());
    }

    /** Reads a report once every event so far has reached the figures, as the dispatch schedule would. */
    protected String report(String path, Instant from, Instant to) throws Exception {
        dispatcher.dispatchPending();
        return mockMvc.perform(authed(get("/api/v1/reports/" + path)
                        .param("from", from.toString()).param("to", to.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
