package vn.essvn.erpcafe.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Issues and parses signed (HS256) JWT access tokens. The token embeds the
 * user's identity and the role they hold at each accessible branch; what those
 * roles permit is resolved per request ({@link RolePermissionCache}), so a role
 * edit applies without waiting for the token to expire.
 *
 * <p>Tokens are signed with the active key of the configured set and name it in their {@code kid}
 * header; any key of the set verifies the tokens it signed, so the active key changes without signing
 * anyone out. Every token is issued by {@value #ISSUER} for {@value #AUDIENCE}, and one naming another
 * issuer or audience is refused.
 */
@Service
public class JwtService {

    static final String ISSUER = "erp-cafe";
    static final String AUDIENCE = "erp-cafe-api";
    private static final String BRANCH_ROLES = "branchRoles";

    private final Map<String, SecretKey> keys;
    private final String activeKid;
    private final Duration accessTokenTtl;
    private final JwtParser parser;

    public JwtService(SecurityProperties properties) {
        SecurityProperties.Jwt jwt = properties.jwt();
        this.keys = jwt.keys().stream().collect(Collectors.toUnmodifiableMap(SecurityProperties.Key::kid,
                k -> Keys.hmacShaKeyFor(k.secret().getBytes(StandardCharsets.UTF_8))));
        this.activeKid = jwt.activeKey().kid();
        this.accessTokenTtl = jwt.accessTokenTtl();
        this.parser = Jwts.parser()
                .keyLocator(this::verificationKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build();
    }

    public String generateAccessToken(AccessClaims claims) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenTtl);
        Map<String, String> branchRoles = new HashMap<>();
        claims.roleByBranch().forEach((branchId, roleId) -> branchRoles.put(branchId.toString(), roleId.toString()));
        return Jwts.builder()
                .header().keyId(activeKid).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(claims.userId().toString())
                .claim("email", claims.email())
                .claim("companyId", claims.companyId().toString())
                .claim(BRANCH_ROLES, branchRoles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(keys.get(activeKid))
                .compact();
    }

    /**
     * Parses and verifies an access token. Throws on invalid/expired tokens, and on tokens
     * without branch roles: those predate per-branch permissions, and a refresh replaces them.
     */
    public AccessClaims parseAccessToken(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        if (!(claims.get(BRANCH_ROLES) instanceof Map<?, ?> raw)) {
            throw new MalformedJwtException("Access token carries no branch roles");
        }
        Map<UUID, UUID> roleByBranch = new HashMap<>();
        raw.forEach((branchId, roleId) ->
                roleByBranch.put(UUID.fromString(String.valueOf(branchId)), UUID.fromString(String.valueOf(roleId))));
        return new AccessClaims(UUID.fromString(claims.getSubject()), claims.get("email", String.class),
                UUID.fromString(claims.get("companyId", String.class)), roleByBranch);
    }

    // A token without a kid predates the key set; a refresh replaces it, as one signed with a removed key.
    private Key verificationKey(Header header) {
        String kid = header instanceof ProtectedHeader protectedHeader ? protectedHeader.getKeyId() : null;
        SecretKey key = kid == null ? null : keys.get(kid);
        if (key == null) {
            throw new UnsupportedJwtException("Access token names no configured signing key");
        }
        return key;
    }
}
