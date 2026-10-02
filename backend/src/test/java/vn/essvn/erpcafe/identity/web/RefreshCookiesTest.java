package vn.essvn.erpcafe.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Class: RefreshCookiesTest
 * Purpose: Pins that the Secure attribute follows the refresh-cookie setting on both the issued and the clearing cookie.
 */
class RefreshCookiesTest {

    private static RefreshCookies cookies(boolean secure) {
        SecurityProperties properties = new SecurityProperties(
                new SecurityProperties.Jwt(
                        List.of(new SecurityProperties.Key("k1", "a-signing-key-of-at-least-32-bytes-long", true)),
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                null, null, null, new SecurityProperties.RefreshCookie(secure));
        return new RefreshCookies(properties);
    }

    @Test
    void secureTrue_marksBothTheIssuedAndTheClearingCookieSecure() {
        RefreshCookies cookies = cookies(true);

        assertThat(cookies.issue("t")).contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(cookies.clear()).contains("Secure", "HttpOnly", "SameSite=Strict", "Max-Age=0");
    }

    @Test
    void secureFalse_marksNeither() {
        RefreshCookies cookies = cookies(false);

        assertThat(cookies.issue("t")).doesNotContain("Secure");
        assertThat(cookies.clear()).doesNotContain("Secure");
    }
}
