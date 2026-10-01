package vn.essvn.erpcafe.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.jayway.jsonpath.JsonPath;

/**
 * Class: ErrorDispatchHttpTest
 * Purpose: Proves over a real port that errors keep their status through the servlet ERROR dispatch, which MockMvc never performs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorDispatchHttpTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        AbstractIntegrationTest.registerDatabase(registry);
    }

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private String token() {
        String body = client().post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"admin@esscafe.vn\",\"password\":\"admin1234\"}")
                .retrieve().body(String.class);
        return JsonPath.read(body, "$.accessToken");
    }

    private int statusOf(String uri) {
        return client().get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    void unknownRoute_is404NotUnauthorized() {
        assertThat(statusOf("/api/v1/no-such-route")).isEqualTo(404);
    }

    @Test
    void missingBranchHeader_is400NotUnauthorized() {
        assertThat(statusOf("/api/v1/reports/summary")).isEqualTo(400);
    }
}
