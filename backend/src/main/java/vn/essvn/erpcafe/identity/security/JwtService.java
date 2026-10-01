package vn.essvn.erpcafe.identity.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Issues and parses signed (HS256) JWT access tokens. The token embeds the
 * user's identity and the role they hold at each accessible branch; what those
 * roles permit is resolved per request ({@link RolePermissionCache}), so a role
 * edit applies without waiting for the token to expire.
 */
@Service
public class JwtService {

    private static final String BRANCH_ROLES = "branchRoles";

    private final SecretKey key;
    private final SecurityProperties.Jwt jwtProps;

    public JwtService(SecurityProperties properties) {
        this.jwtProps = properties.jwt();
        this.key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(AccessClaims claims) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProps.accessTokenTtl());
        Map<String, String> branchRoles = new HashMap<>();
        claims.roleByBranch().forEach((branchId, roleId) -> branchRoles.put(branchId.toString(), roleId.toString()));
        return Jwts.builder()
                .subject(claims.userId().toString())
                .claim("email", claims.email())
                .claim("companyId", claims.companyId().toString())
                .claim(BRANCH_ROLES, branchRoles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * Parses and verifies an access token. Throws on invalid/expired tokens, and on tokens
     * without branch roles: those predate per-branch permissions, and a refresh replaces them.
     */
    public AccessClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!(claims.get(BRANCH_ROLES) instanceof Map<?, ?> raw)) {
            throw new MalformedJwtException("Access token carries no branch roles");
        }
        Map<UUID, UUID> roleByBranch = new HashMap<>();
        raw.forEach((branchId, roleId) ->
                roleByBranch.put(UUID.fromString(String.valueOf(branchId)), UUID.fromString(String.valueOf(roleId))));
        return new AccessClaims(UUID.fromString(claims.getSubject()), claims.get("email", String.class),
                UUID.fromString(claims.get("companyId", String.class)), roleByBranch);
    }
}
