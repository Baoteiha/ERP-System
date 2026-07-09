package vn.essvn.erpcafe.identity.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.identity.application.AuthService;
import vn.essvn.erpcafe.identity.application.TokenPair;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.identity.web.AuthDtos.LoginRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.MeResponse;
import vn.essvn.erpcafe.identity.web.AuthDtos.RefreshRequest;
import vn.essvn.erpcafe.identity.web.AuthDtos.TokenResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        TokenPair tokens = authService.login(request.email(), request.password());
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

    @GetMapping("/me")
    public MeResponse me() {
        AuthPrincipal p = currentUser.require();
        return new MeResponse(p.userId(), p.email(), p.companyId(),
                p.branchIds(), p.roles(), p.permissions());
    }
}
