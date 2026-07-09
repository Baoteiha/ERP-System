package vn.essvn.erpcafe.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.JwtService;

/**
 * Authentication use cases: login, token refresh (with rotation), and logout.
 * Access tokens are stateless JWTs; refresh tokens are opaque, stored hashed,
 * and revocable.
 */
@Service
@Transactional
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    public AuthService(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            RoleRepository roleRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JwtService jwtService, SecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
    }

    public TokenPair login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new BusinessRuleException("Invalid email or password"));
        if (!user.isActive()) {
            throw new BusinessRuleException("Account is disabled");
        }
        return issueTokens(user);
    }

    public TokenPair refresh(String rawRefreshToken) {
        // We store only the SHA-256 hash, so look up by hash — a DB leak never exposes usable tokens.
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessRuleException("Invalid refresh token"));
        if (!stored.isActive(Instant.now())) {
            throw new BusinessRuleException("Refresh token expired or revoked");
        }
        // Rotation: each refresh token is single-use. Revoke it and mint a fresh pair, so a
        // stolen-and-replayed token is caught (the legitimate client already rotated it away).
        stored.revoke();
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessRuleException("User no longer exists"));
        if (!user.isActive()) {
            throw new BusinessRuleException("Account is disabled");
        }
        return issueTokens(user);
    }

    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(RefreshToken::revoke);
    }

    /** Builds the security principal for a user from their per-branch role grants. */
    @Transactional(readOnly = true)
    public AuthPrincipal buildPrincipal(User user) {
        List<UserBranchAccess> accesses = accessRepository.findByUserId(user.getId());
        Set<UUID> branchIds = accesses.stream()
                .map(UserBranchAccess::getBranchId).collect(Collectors.toSet());
        List<UUID> roleIds = accesses.stream()
                .map(UserBranchAccess::getRoleId).distinct().toList();
        List<Role> roles = roleRepository.findAllById(roleIds);
        Set<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toSet());
        Set<String> permissions = roles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getName())
                .collect(Collectors.toSet());
        return new AuthPrincipal(user.getId(), user.getEmail(), user.getCompanyId(),
                branchIds, permissions, roleNames);
    }

    private TokenPair issueTokens(User user) {
        AuthPrincipal principal = buildPrincipal(user);
        // Access token: stateless JWT, short-lived, carries permissions/branches (no DB lookup per request).
        String accessToken = jwtService.generateAccessToken(principal);
        // Refresh token: opaque random string returned to the client; only its hash is persisted.
        String rawRefresh = randomToken();
        Instant expiry = Instant.now().plus(securityProperties.jwt().refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user.getId(), sha256(rawRefresh), expiry));
        return new TokenPair(accessToken, rawRefresh);
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
