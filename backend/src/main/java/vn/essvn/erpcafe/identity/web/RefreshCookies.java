package vn.essvn.erpcafe.identity.web;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.common.exception.UnauthorizedException;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Class: RefreshCookies
 * Purpose: Builds, clears and reads the HttpOnly cookie that carries the refresh token, and checks the header that guards its use.
 */
@Component
class RefreshCookies {

    static final String NAME = "erpcafe_refresh";
    static final String PATH = ApiVersions.V1 + "/auth";
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "erpcafe";

    private final boolean secure;
    private final Duration maxAge;

    RefreshCookies(SecurityProperties properties) {
        this.secure = properties.refreshCookie().secure();
        this.maxAge = properties.jwt().refreshTokenTtl();
    }

    /** The Set-Cookie value that hands the browser a refresh token for the auth endpoints alone. */
    String issue(String refreshToken) {
        return cookie(refreshToken, maxAge).toString();
    }

    /** The Set-Cookie value that expires the cookie: same name, path and attributes, Max-Age 0. */
    String clear() {
        return cookie("", Duration.ZERO).toString();
    }

    /**
     * A cross-site form or fetch cannot add a custom header without a CORS preflight, which this API
     * never grants, so the header's presence stands in for a CSRF token on the calls the cookie authorises.
     */
    void requireHeader(HttpServletRequest request) {
        if (!CSRF_VALUE.equals(request.getHeader(CSRF_HEADER))) {
            throw new ForbiddenException("Missing " + CSRF_HEADER + " header");
        }
    }

    String require(HttpServletRequest request) {
        String token = token(request);
        if (token == null) {
            throw new UnauthorizedException("No refresh token");
        }
        return token;
    }

    /** The cookie's value, or null when it is absent or empty. */
    String token(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }

    private ResponseCookie cookie(String value, Duration age) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(age)
                .build();
    }
}
