package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: SchemaHygieneIntegrationTest
 * Purpose: Proves deleted names and codes can be reused while live duplicates still conflict.
 */
class SchemaHygieneIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** A scratch schema in the test database, so the app's own schema is never migrated backwards. */
    private static final String SCHEMA = "legacy_schema_hygiene";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BranchRepository branches;
    @Autowired
    private UserBranchAccessRepository grants;
    @Autowired
    private OrderRepository orders;

    private String token;
    private String branchId;
    private final List<UUID> createdBranches = new ArrayList<>();
    private final List<Order> createdOrders = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(),
                "$.branchIds")).get(0);
    }

    @AfterEach
    void cleanUp() {
        orders.deleteAll(createdOrders);
        for (UUID id : createdBranches) {
            grants.deleteAll(grants.findByBranchId(id));
            branches.findById(id).ifPresent(branches::delete);
        }
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String createOk(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void deleteOk(String url, String id) throws Exception {
        mockMvc.perform(authed(delete(url + "/" + id))).andExpect(status().isNoContent());
    }

    /** Creates, deletes, then creates again with the same body; the second create must succeed. */
    private void recreatable(String url, String body) throws Exception {
        String id = JsonPath.read(createOk(url, body), "$.id");
        deleteOk(url, id);
        String again = JsonPath.read(createOk(url, body), "$.id");
        assertThat(again).isNotEqualTo(id);
        deleteOk(url, again);
    }

    @Test
    void deletedSku_canBeUsedAgain() throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = JsonPath.read(createOk("/api/v1/categories",
                "{\"name\":\"Hyg Cat %d\",\"displayOrder\":1}".formatted(n)), "$.id");
        recreatable("/api/v1/products", ("{\"categoryId\":\"%s\",\"name\":\"Latte %d\",\"sku\":\"LAT1-%d\","
                + "\"basePrice\":45000}").formatted(categoryId, n, n));
        deleteOk("/api/v1/categories", categoryId);
    }

    @Test
    void deletedCategoryName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/categories", "{\"name\":\"Hyg Cat %d\",\"displayOrder\":1}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedIngredientName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/ingredients", "{\"name\":\"Hyg Bean %d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedSupplierName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/suppliers", "{\"name\":\"Hyg Roaster %d\"}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedBranchCode_canBeUsedAgain() throws Exception {
        int n = SEQ.incrementAndGet();
        String body = "{\"name\":\"Hyg Branch %d\",\"code\":\"HY%d\"}".formatted(n, n);
        String first = JsonPath.read(createOk("/api/v1/branches", body), "$.id");
        createdBranches.add(UUID.fromString(first));
        deleteOk("/api/v1/branches", first);
        String second = JsonPath.read(createOk("/api/v1/branches", body), "$.id");
        createdBranches.add(UUID.fromString(second));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void liveDuplicates_stillConflict() throws Exception {
        String body = "{\"name\":\"Hyg Dup %d\"}".formatted(SEQ.incrementAndGet());
        String id = JsonPath.read(createOk("/api/v1/suppliers", body), "$.id");
        mockMvc.perform(authed(post("/api/v1/suppliers")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        deleteOk("/api/v1/suppliers", id);
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }
}
