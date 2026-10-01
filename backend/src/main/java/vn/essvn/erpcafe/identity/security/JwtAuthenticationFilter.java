package vn.essvn.erpcafe.identity.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.security.RolePermissionCache.RoleGrant;

/**
 * Authenticates each request from its Bearer access token and establishes the
 * active branch from the {@code X-Branch-Id} header.
 *
 * <ul>
 *   <li>Invalid/expired token → 401.</li>
 *   <li>Valid token but {@code X-Branch-Id} not among the token's branches still granted → 403.</li>
 *   <li>No token → request continues unauthenticated (protected routes then 401).</li>
 * </ul>
 *
 * <p>Authorities are what the user's role at the active branch permits, plus the
 * company-level permissions they hold at any branch; a request naming no branch
 * gets every branch's (ADR-0007). It can reach no branch's data that way: every
 * branch-scoped operation requires the header.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String BRANCH_HEADER = "X-Branch-Id";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final RolePermissionCache roleCache;
    private final UserGrantCache grantCache;

    public JwtAuthenticationFilter(JwtService jwtService, RolePermissionCache roleCache, UserGrantCache grantCache) {
        this.jwtService = jwtService;
        this.roleCache = roleCache;
        this.grantCache = grantCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            filterChain.doFilter(request, response);
            return;
        }

        AccessClaims claims;
        TenantContext.Entered tenant;
        try {
            claims = jwtService.parseAccessToken(header.substring(BEARER.length()));
            // entered first: every lookup below, and the whole request after it, reads only this company's
            // rows. A token naming no real company (one of the persistence identifiers) is as bad as a forged one.
            tenant = TenantContext.enter(claims.companyId());
        } catch (Exception ex) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        try {
            // the token's branch roles count only while still granted: a revoke or a closed branch ends them at once
            Map<UUID, UUID> current = grantCache.current(claims.userId(), claims.companyId());
            Map<UUID, UUID> roleByBranch = new HashMap<>();
            claims.roleByBranch().forEach((branchId, roleId) -> {
                if (roleId.equals(current.get(branchId))) {
                    roleByBranch.put(branchId, roleId);
                }
            });

            // Resolve the active branch, if one was requested.
            UUID activeBranch = null;
            String branchHeader = request.getHeader(BRANCH_HEADER);
            if (branchHeader != null && !branchHeader.isBlank()) {
                try {
                    activeBranch = UUID.fromString(branchHeader.trim());
                } catch (IllegalArgumentException ex) {
                    writeError(response, HttpStatus.BAD_REQUEST, "Malformed " + BRANCH_HEADER);
                    return;
                }
                if (!roleByBranch.containsKey(activeBranch)) {
                    writeError(response, HttpStatus.FORBIDDEN, "No access to branch " + activeBranch);
                    return;
                }
                BranchContext.set(activeBranch);
            }

            SecurityContextHolder.getContext().setAuthentication(authenticate(claims, roleByBranch, activeBranch));

            filterChain.doFilter(request, response);
        } finally {
            BranchContext.clear();
            // back to whatever was in force before the request: nothing, on a servlet thread
            tenant.close();
            SecurityContextHolder.clearContext();
        }
    }

    /** Resolves the branch roles still granted, and the authorities in force at {@code activeBranch} (null: none). */
    private UsernamePasswordAuthenticationToken authenticate(AccessClaims claims, Map<UUID, UUID> roleByBranch,
            UUID activeBranch) {
        Map<UUID, Set<String>> permissionsByBranch = new HashMap<>();
        Set<String> roles = new HashSet<>();
        Set<String> rolesInForce = new HashSet<>();
        roleByBranch.forEach((branchId, roleId) -> {
            Optional<RoleGrant> role = roleCache.get(roleId);
            permissionsByBranch.put(branchId, role.map(RoleGrant::permissions).orElse(Set.of()));
            role.ifPresent(r -> {
                roles.add(r.name());
                if (activeBranch == null || activeBranch.equals(branchId)) {
                    rolesInForce.add(r.name());
                }
            });
        });
        Set<String> held = new HashSet<>();
        permissionsByBranch.values().forEach(held::addAll);
        Set<String> inForce = new HashSet<>(activeBranch == null ? held : permissionsByBranch.get(activeBranch));
        held.stream().filter(Permissions.COMPANY_LEVEL::contains).forEach(inForce::add);
        AuthPrincipal principal = new AuthPrincipal(claims.userId(), claims.email(), claims.companyId(),
                Map.copyOf(permissionsByBranch), Set.copyOf(inForce), Set.copyOf(roles));

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        inForce.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        rolesInForce.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":%d,\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(status.value(), codeFor(status), status.getReasonPhrase(), detail));
    }

    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            default -> "BAD_REQUEST";
        };
    }
}
