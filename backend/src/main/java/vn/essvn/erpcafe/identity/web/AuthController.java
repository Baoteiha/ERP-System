package vn.essvn.erpcafe.identity.web;

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
import vn.essvn.erpcafe.identity.web.AuthDtos.RefreshRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.TokenResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, PasswordResetService passwordResetService,
            CurrentUser currentUser) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        TokenPair tokens = authService.login(request.email(), request.password(), http.getRemoteAddr());
        return TokenResponse.bearer(tokens.accessToken(), tokens.refreshToken());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        TokenPair tokens = authService.refresh(request.refreshToken());
        return TokenResponse.bearer(tokens.accessToken(), tokens.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
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
