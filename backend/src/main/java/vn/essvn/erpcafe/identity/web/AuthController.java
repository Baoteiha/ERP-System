package vn.essvn.erpcafe.identity.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.identity.application.AuthService;
import vn.essvn.erpcafe.identity.application.PasswordResetService;
import vn.essvn.erpcafe.identity.application.TokenPair;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.identity.web.AuthDtos.LoginRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.MeResponse;
import vn.essvn.erpcafe.identity.web.AuthDtos.PasswordResetConfirmRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.PasswordResetRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.TokenResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final CurrentUser currentUser;
    private final RefreshCookies refreshCookies;

    public AuthController(AuthService authService, PasswordResetService passwordResetService,
            CurrentUser currentUser, RefreshCookies refreshCookies) {
        this.refreshCookies = refreshCookies;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        TokenPair tokens = authService.login(request.email(), request.password(), http.getRemoteAddr());
        return withRefreshCookie(tokens);
    }

    /** The refresh token comes from the cookie alone; a body, if sent, is ignored. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest http) {
        refreshCookies.requireHeader(http);
        return withRefreshCookie(authService.refresh(refreshCookies.require(http)));
    }

    /** Clears the cookie whether or not the token behind it is still live. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        refreshCookies.requireHeader(http);
        String token = refreshCookies.token(http);
        if (token != null) {
            authService.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookies.clear()).build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(TokenPair tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(tokens.refreshToken()))
                .body(TokenResponse.bearer(tokens.accessToken()));
    }

    /** Always 202, whether or not the email has an account, so the answer reveals none. */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthPrincipal p = currentUser.require();
        return new MeResponse(p.userId(), p.email(), p.companyId(),
                p.branchIds(), p.roles(), p.allPermissions(), p.permissionsByBranch());
    }
}
