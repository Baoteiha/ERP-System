package vn.essvn.erpcafe.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

/**
 * Base class for integration tests. Boots the full application context against
 * a real, throwaway PostgreSQL managed by Testcontainers, and exposes MockMvc
 * for driving the HTTP layer (security filters included).
 *
 * <p>Uses the Testcontainers singleton pattern: one container is started for the
 * whole test JVM and reused across all test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registerDatabase(registry);
    }

    /**
     * The app connects as erp_app (application-test.yml), which the migrations create; Flyway and
     * the system work connect as the container's superuser, the schema owner.
     */
    static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /** Everything the test database server has logged so far. */
    protected static String databaseLog() {
        return POSTGRES.getLogs();
    }

    @Autowired
    protected MockMvc mockMvc;

    // --- shared auth helpers ---

    /** Logs in and returns the raw token response body (for tests needing the refresh token too). */
    protected String loginBody(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    protected String accessToken(String email, String password) throws Exception {
        return JsonPath.read(loginBody(email, password), "$.accessToken");
    }

    /** The bootstrap admin's access token (seeded by DataInitializer). */
    protected String adminToken() throws Exception {
        return accessToken("admin@esscafe.vn", "admin1234");
    }

    protected String authHeader(String token) {
        return "Bearer " + token;
    }
}
