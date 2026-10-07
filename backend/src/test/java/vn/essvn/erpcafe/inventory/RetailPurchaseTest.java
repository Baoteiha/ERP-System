package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RetailPurchaseTest
 * Purpose: A retail purchase saves a received order and its stock in one step, all or nothing, under its own permission.
 */
class RetailPurchaseTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private RoleRepository roles;

    private final List<UUID> createdRoles = new ArrayList<>();
    private String token;
    private String branchId;
    private String coffee;
    private String shop;
    private String bag;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();
        coffee = id(send(post("/api/v1/ingredients"), token, branchId,
                "{\"name\":\"RetailCoffee%d\",\"baseUnit\":\"g\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), token, branchId, "{\"name\":\"Tạp hóa Cô Ba %d\"}".formatted(n)));
        bag = id(send(post("/api/v1/items"), token, branchId, ("{\"name\":\"Cà phê bịch 500g\",\"supplierId\":\"%s\","
                + "\"ingredientId\":\"%s\",\"unit\":\"g\",\"packLabel\":\"bịch\",\"packSize\":500}").formatted(shop, coffee)));
    }

    /** A branch holding stock cannot be deleted, so the test works in the owner's own branch and removes only its role. */
    @AfterEach
    void cleanUp() {
        createdRoles.forEach(roles::deleteById);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String tok, String branch, String body) throws Exception {
        b.header(HttpHeaders.AUTHORIZATION, authHeader(tok));
        if (branch != null) {
            b.header("X-Branch-Id", branch);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(b);
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    private BigDecimal stock(String tok, String branch, String field) throws Exception {
        String json = body(send(get("/api/v1/stock"), tok, branch, null));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(coffee, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private int orderCount() throws Exception {
        return ((List<?>) JsonPath.read(body(send(get("/api/v1/purchase-orders"), token, branchId, null)),
                "$.content")).size();
    }

    @Test
    void retailPurchase_savesAReceivedRetailOrder_andStock() throws Exception {
        String json = body(send(post("/api/v1/purchase-orders/retail"), token, branchId,
                ("{\"supplierId\":\"%s\",\"note\":\"ran out at 9pm\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,"
                        + "\"unitCost\":95000}]}").formatted(shop, bag)));

        assertThat((String) JsonPath.read(json, "$.kind")).isEqualTo("RETAIL");
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("RECEIVED");
        assertThat((List<?>) JsonPath.read(json, "$.receipts")).hasSize(1);
        assertThat(stock(token, branchId, "quantityOnHand")).isEqualByComparingTo("500");
        assertThat(stock(token, branchId, "avgUnitCost.amount")).isEqualByComparingTo("190");
    }

    @Test
    void aFailingLine_savesNothing() throws Exception {
        String otherShop = id(send(post("/api/v1/suppliers"), token, branchId,
                "{\"name\":\"OtherShop%d\"}".formatted(SEQ.incrementAndGet())));
        String foreignItem = itemFor(token, branchId, otherShop, coffee, "g");
        int before = orderCount();

        send(post("/api/v1/purchase-orders/retail"), token, branchId,
                ("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,\"unitCost\":95000},"
                        + "{\"itemId\":\"%s\",\"quantity\":1,\"unitCost\":1}]}").formatted(shop, bag, foreignItem))
                .andExpect(status().isUnprocessableEntity());

        assertThat(orderCount()).isEqualTo(before);
        assertThat(stock(token, branchId, "quantityOnHand")).isNull();
    }

    @Test
    void retailOnly_recordsARetailPurchase_butCannotOrderOrDeclareItems() throws Exception {
        int n = SEQ.incrementAndGet();
        String branch = branchId;
        String roleId = id(send(post("/api/v1/roles"), token, null,
                "{\"name\":\"RETAIL_ONLY_%d\",\"description\":\"d\",\"permissions\":[\"inventory:read\",\"purchasing:retail\"]}"
                        .formatted(n)));
        createdRoles.add(UUID.fromString(roleId));
        String email = "retail%d@t.test".formatted(n);
        String userId = id(send(post("/api/v1/users"), token, null,
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Retail Only\"}".formatted(email)));
        body(send(post("/api/v1/users/" + userId + "/access"), token, null,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branch, roleId)));
        String cashier = accessToken(email, "password123");

        send(post("/api/v1/purchase-orders/retail"), cashier, branch,
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":2,\"unitCost\":95000}]}"
                        .formatted(shop, bag))
                .andExpect(status().isCreated());
        send(post("/api/v1/purchase-orders"), cashier, branch,
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}]}"
                        .formatted(shop, bag))
                .andExpect(status().isForbidden());
        send(post("/api/v1/items"), cashier, branch,
                "{\"name\":\"x\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"g\"}".formatted(shop, coffee))
                .andExpect(status().isForbidden());

        assertThat(stock(cashier, branch, "quantityOnHand")).isEqualByComparingTo("1000");
    }
}
