package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vn.essvn.erpcafe.identity.application.RefreshTokenPurger;
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.JwtAuthenticationFilter;
import vn.essvn.erpcafe.identity.security.JwtService;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: LoginHardeningTest
 * Purpose: Pins the login timing, case-insensitive email, per-address throttle, token purge and single-filter rules.
 */
@TestPropertySource(properties = "app.security.login.max-failures-per-address=20")
class LoginHardeningTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private RefreshTokenPurger purger;
    @Autowired
    private ObjectProvider<FilterRegistrationBean<?>> filterRegistrations;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;
    @MockitoSpyBean
    private JwtService jwtService;

    private int attempt(String email, String password, String address) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getStatus();
    }

    private String uniqueAddress() {
        return "10.25.%d.%d".formatted(SEQ.incrementAndGet() / 250, SEQ.get() % 250 + 1);
    }

    @Test
    void login_withDifferentlyCasedEmail_works() throws Exception {
        assertThat(attempt("Admin@EssCafe.VN", "admin1234", uniqueAddress())).isEqualTo(200);
    }

    @Test
    void create_withEmailDifferingOnlyByCase_isRejectedAsDuplicate() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ADMIN@esscafe.vn\",\"password\":\"whatever123\",\"fullName\":\"Dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(userRepository.findByEmail("admin@esscafe.vn")).isPresent();
        assertThat(userRepository.findByEmail("ADMIN@esscafe.vn")).isEmpty();
    }

    @Test
    void create_storesTheEmailLowerCased() throws Exception {
        String email = "Mixed.Case%d@EssCafe.vn".formatted(SEQ.incrementAndGet());
        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"whatever123\",\"fullName\":\"Mixed\"}"
                                .formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
    }

    @Test
    void login_forUnknownEmail_stillRunsThePasswordEncoder() throws Exception {
        clearInvocations(passwordEncoder);

        assertThat(attempt("nobody%d@esscafe.vn".formatted(SEQ.incrementAndGet()), "whatever", uniqueAddress()))
                .isEqualTo(401);

        verify(passwordEncoder, times(1)).matches(eq("whatever"), any());
    }

    @Test
    void twentyFailedLogins_acrossEmails_fromOneAddress_areThrottled() throws Exception {
        String address = uniqueAddress();
        for (int i = 0; i < 20; i++) {
            assertThat(attempt("spray%d@esscafe.vn".formatted(SEQ.incrementAndGet()), "wrong", address))
                    .isEqualTo(401);
        }

        assertThat(attempt("spray-another@esscafe.vn", "wrong", address)).isEqualTo(429);
        // a different address is unaffected
        assertThat(attempt("admin@esscafe.vn", "admin1234", uniqueAddress())).isEqualTo(200);
    }

    @Test
    void purger_deletesTokensExpiredOverAWeekAgo_andKeepsTheRest() {
        UUID userId = userRepository.findByEmail("admin@esscafe.vn").orElseThrow().getId();
        Instant now = Instant.now();
        RefreshToken longGone = save(userId, now.minus(Duration.ofDays(8)));
        RefreshToken justExpired = save(userId, now.minus(Duration.ofDays(1)));
        RefreshToken live = save(userId, now.plus(Duration.ofDays(1)));

        purger.purgeExpired();

        assertThat(refreshTokenRepository.findById(longGone.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(justExpired.getId())).isPresent();
        assertThat(refreshTokenRepository.findById(live.getId())).isPresent();
        refreshTokenRepository.delete(justExpired);
        refreshTokenRepository.delete(live);
    }

    private RefreshToken save(UUID userId, Instant expiresAt) {
        String hash = "%064d".formatted(SEQ.incrementAndGet() + System.nanoTime());
        return refreshTokenRepository.save(new RefreshToken(userId, hash.substring(0, 64), expiresAt,
                UUID.randomUUID()));
    }

    @Test
    void jwtFilter_isOnlyRegisteredInTheSecurityChain() {
        FilterRegistrationBean<?> registration = filterRegistrations.stream()
                .filter(r -> r.getFilter() instanceof JwtAuthenticationFilter)
                .findFirst().orElseThrow();

        assertThat(registration.isEnabled()).isFalse();
    }

    @Test
    void jwtFilter_authenticatesARequestOnce() throws Exception {
        String token = adminToken();
        clearInvocations(jwtService);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk());

        verify(jwtService, times(1)).parseAccessToken(token);
        verify(jwtService, atLeastOnce()).parseAccessToken(any());
    }
}
