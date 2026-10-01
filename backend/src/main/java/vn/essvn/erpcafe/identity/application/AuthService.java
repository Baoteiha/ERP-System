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
import vn.essvn.erpcafe.common.exception.UnauthorizedException;
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
import vn.essvn.erpcafe.identity.security.LoginAttemptService;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Authentication use cases: login, token refresh (with rotation), and logout.
 * Access tokens are stateless JWTs; refresh tokens are opaque, stored hashed,
 * and revocable.
 */
@Service
@Transactional
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // BCrypt, cost 10 like the real encoder, of a throwaway string
    private static final String DUMMY_HASH = "$2y$10$z9vj/OETvQdVWGLhV4ih8edtiq1fW2b3c1CM4WC2pTyEeq8ztpVTK";

    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final SecurityProperties securityProperties;
    private final OrganizationApi organizationApi;

    public AuthService(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            RoleRepository roleRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JwtService jwtService,
            LoginAttemptService loginAttemptService, RefreshTokenFamilyRevoker familyRevoker,
            SecurityProperties securityProperties, OrganizationApi organizationApi) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.familyRevoker = familyRevoker;
        this.securityProperties = securityProperties;
        this.organizationApi = organizationApi;
    }

    /**
     * Verifies credentials and issues a token pair. {@code clientKey} is the caller's
     * address; it only ever feeds the failed-attempt throttle.
     */
    public TokenPair login(String email, String rawPassword, String clientKey) {
        String normalizedEmail = User.normalizeEmail(email);
        loginAttemptService.checkNotBlocked(normalizedEmail, clientKey);

        User found = userRepository.findByEmail(normalizedEmail).orElse(null);
        // An unknown email still pays for one hash check, so response time does not reveal which emails exist.
        boolean passwordMatches = passwordEncoder.matches(rawPassword,
                found != null ? found.getPasswordHash() : DUMMY_HASH);
        User user = found != null && passwordMatches ? found : null;
        if (user == null) {
            loginAttemptService.recordFailure(normalizedEmail, clientKey);
            throw new UnauthorizedException("Invalid email or password");
        }
        if (!user.isActive()) {
            loginAttemptService.recordFailure(normalizedEmail, clientKey);
            throw new BusinessRuleException("Account is disabled");
        }

        loginAttemptService.reset(normalizedEmail, clientKey);
        return issueTokens(user);
    }

    public TokenPair refresh(String rawRefreshToken) {
        // We store only the SHA-256 hash, so look up by hash — a DB leak never exposes usable tokens.
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessRuleException("Invalid refresh token"));

        // Reuse detection: this token was already rotated away, so either it was stolen and
        // replayed, or the legitimate holder's copy was. Either way the family is compromised.
        // Revoke every live token descended from that login and force a fresh sign-in.
        if (stored.isRevoked()) {
            familyRevoker.revokeFamily(stored.getFamilyId());
            throw new BusinessRuleException(
                    "Refresh token has already been used; all sessions have been revoked");
        }
        if (!stored.isActive(Instant.now())) {
            throw new BusinessRuleException("Refresh token expired or revoked");
        }

        // Rotation: each refresh token is single-use. The replacement stays in the same family.
        stored.revoke();
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessRuleException("User no longer exists"));
        if (!user.isActive()) {
            throw new BusinessRuleException("Account is disabled");
        }
        return issueTokens(user, stored.getFamilyId());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(RefreshToken::revoke);
    }

    /** Builds the security principal for a user from their per-branch role grants. */
    @Transactional(readOnly = true)
    public AuthPrincipal buildPrincipal(User user) {
        // a grant on a deleted or deactivated branch grants nothing: not the branch, not its role's permissions
        Set<UUID> liveBranchIds = organizationApi.listActiveBranches(user.getCompanyId()).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        List<UserBranchAccess> accesses = accessRepository.findByUserId(user.getId()).stream()
                .filter(a -> liveBranchIds.contains(a.getBranchId()))
                .toList();
        Set<UUID> branchIds = accesses.stream()
                .map(UserBranchAccess::getBranchId)
                .collect(Collectors.toSet());
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

    /** A login starts a brand-new token family. */
    private TokenPair issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        AuthPrincipal principal = buildPrincipal(user);
        // Access token: stateless JWT, short-lived, carries permissions/branches (no DB lookup per request).
        String accessToken = jwtService.generateAccessToken(principal);
        // Refresh token: opaque random string returned to the client; only its hash is persisted.
        String rawRefresh = randomToken();
        Instant expiry = Instant.now().plus(securityProperties.jwt().refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user.getId(), sha256(rawRefresh), expiry, familyId));
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
