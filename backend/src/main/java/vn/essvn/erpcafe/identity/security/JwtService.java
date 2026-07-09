package vn.essvn.erpcafe.identity.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Issues and parses signed (HS256) JWT access tokens. The token embeds the
 * user's identity, granted permissions, roles, and accessible branch ids, so
 * request authorization needs no database lookup during the token's lifetime.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final SecurityProperties.Jwt jwtProps;

    public JwtService(SecurityProperties properties) {
        this.jwtProps = properties.jwt();
        this.key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProps.accessTokenTtl());
        return Jwts.builder()
                .subject(principal.userId().toString())
                .claim("email", principal.email())
                .claim("companyId", principal.companyId().toString())
                .claim("perms", List.copyOf(principal.permissions()))
                .claim("roles", List.copyOf(principal.roles()))
                .claim("branches", principal.branchIds().stream().map(UUID::toString).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /** Parses and verifies an access token, reconstructing the principal. Throws on invalid/expired tokens. */
    public AuthPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        UUID companyId = UUID.fromString(claims.get("companyId", String.class));
        Set<String> perms = toStringSet(claims.get("perms", List.class));
        Set<String> roles = toStringSet(claims.get("roles", List.class));
        Set<UUID> branches = toStringSet(claims.get("branches", List.class)).stream()
                .map(UUID::fromString).collect(Collectors.toSet());

        return new AuthPrincipal(userId, email, companyId, branches, perms, roles);
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(List<?> raw) {
        if (raw == null) {
            return Set.of();
        }
        return ((List<Object>) raw).stream().map(String::valueOf).collect(Collectors.toSet());
    }
}
