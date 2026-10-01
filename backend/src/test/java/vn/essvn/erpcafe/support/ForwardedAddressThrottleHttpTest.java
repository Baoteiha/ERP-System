package vn.essvn.erpcafe.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ForwardedAddressThrottleHttpTest
 * Purpose: Proves over a real port that X-Forwarded-For from an untrusted peer cannot dodge the per-address login throttle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.security.login.max-failures-per-address=5",
            // the test client (127.0.0.1) is deliberately not a trusted proxy
            "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.\\d{1,3}"
        })
@ActiveProfiles("test")
class ForwardedAddressThrottleHttpTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        AbstractIntegrationTest.registerDatabase(registry);
    }

    @LocalServerPort
    private int port;

    private int failedLogin(int n, String spoofedAddress) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/auth/login")
                .header("X-Forwarded-For", spoofedAddress)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"spoof%d@esscafe.vn\",\"password\":\"wrong\"}".formatted(n))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    void rotatingForwardedFor_fromAnUntrustedPeer_sharesOneAddressBucket() {
        for (int i = 0; i < 5; i++) {
            assertThat(failedLogin(i, "203.0.113." + (i + 1))).isEqualTo(401);
        }

        assertThat(failedLogin(99, "203.0.113.200")).isEqualTo(429);
    }
}
