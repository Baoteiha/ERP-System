package vn.essvn.erpcafe.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.UnauthorizedException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.AccessClaims;
import vn.essvn.erpcafe.identity.security.JwtService;
import vn.essvn.erpcafe.identity.security.LoginAttemptService;
import vn.essvn.erpcafe.identity.security.UserGrantCache;

/**
 * Authentication use cases: login, token refresh (with rotation), and logout.
 * Access tokens are stateless JWTs; refresh tokens are opaque, stored hashed,
 * and revocable.
 *
 * <p>Login, refresh and logout run as the system ({@link TenantContext#runAsSystem}), on the schema
 * owner's connection ({@link SystemTransactions}): the user, and so the company, is unknown until the
 * email or the refresh token is looked up. Every query they make names its row explicitly (email,
 * user, token hash, company).
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // BCrypt, cost 10 like the real encoder, of a throwaway string
    private static final String DUMMY_HASH = "$2y$10$z9vj/OETvQdVWGLhV4ih8edtiq1fW2b3c1CM4WC2pTyEeq8ztpVTK";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final SecurityProperties securityProperties;
    private final UserGrantCache grantCache;
    private final SystemTransactions system;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JwtService jwtService,
            LoginAttemptService loginAttemptService, SecurityProperties securityProperties,
            UserGrantCache grantCache, SystemTransactions system) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.securityProperties = securityProperties;
        this.grantCache = grantCache;
        this.system = system;
    }

    /**
     * Verifies credentials and issues a token pair. {@code clientKey} is the caller's
     * address; it only ever feeds the failed-attempt throttle.
     */
    public TokenPair login(String email, String rawPassword, String clientKey) {
        String normalizedEmail = User.normalizeEmail(email);
        loginAttemptService.checkNotBlocked(normalizedEmail, clientKey);

        // The hash check runs between two system transactions, so it never holds an owner connection.
        User found = system.run(() -> userRepository.findByEmail(normalizedEmail)).orElse(null);
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
        return system.run(() -> issueTokens(user));
    }

    public TokenPair refresh(String rawRefreshToken) {
        Rotation rotation = system.run(() -> rotate(rawRefreshToken));
        if (rotation.replayedFamily() != null) {
            // Revoked in a transaction of its own, begun after the lookup's has ended: it commits although
            // this request then fails, and the request never holds two owner connections at once.
            system.run(() -> refreshTokenRepository.revokeFamily(rotation.replayedFamily()));
            throw new BusinessRuleException(
                    "Refresh token has already been used; all sessions have been revoked");
        }
        return rotation.tokens();
    }

    /** The rotated token pair, or the family of a token presented again after it was rotated away. */
    private record Rotation(TokenPair tokens, UUID replayedFamily) {
    }

    private Rotation rotate(String rawRefreshToken) {
        // We store only the SHA-256 hash, so look up by hash — a DB leak never exposes usable tokens.
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessRuleException("Invalid refresh token"));

        // Reuse detection: this token was already rotated away, so either it was stolen and
        // replayed, or the legitimate holder's copy was. Either way the family is compromised.
        // Revoke every live token descended from that login and force a fresh sign-in.
        if (stored.isRevoked()) {
            return new Rotation(null, stored.getFamilyId());
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
        return new Rotation(issueTokens(user, stored.getFamilyId()), null);
    }

    // the token is the credential, and a request presenting it may carry no company at all
    public void logout(String rawRefreshToken) {
        system.run(() -> refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(RefreshToken::revoke));
    }

    /** What a user's access token asserts: their role at each live branch and their auth version, read afresh. */
    private AccessClaims accessClaimsFor(User user) {
        UserGrantCache.Grants grants = grantCache.reload(user.getId(), user.getCompanyId())
                .orElseThrow(() -> new BusinessRuleException("User no longer exists"));
        return new AccessClaims(user.getId(), user.getEmail(), user.getCompanyId(), grants.roleByBranch(),
                grants.authVersion());
    }

    /** A login starts a brand-new token family. */
    private TokenPair issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        // Access token: stateless JWT, short-lived, carries the role held at each branch.
        String accessToken = jwtService.generateAccessToken(accessClaimsFor(user));
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
