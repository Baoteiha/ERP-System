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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.security.RolePermissionCache.RoleGrant;
import vn.essvn.erpcafe.identity.security.UserGrantCache.Grants;

/**
 * Authenticates each request from its Bearer access token and establishes the
 * active branch from the {@code X-Branch-Id} header.
 *
 * <ul>
 *   <li>Invalid/expired token → 401.</li>
 *   <li>A token whose auth version is no longer the user's (their status or password changed, or a
 *       branch they held closed since it was issued) → 401, as is one naming another role at the
 *       {@code X-Branch-Id} branch than the user now holds there: a refresh brings either up to date.</li>
 *   <li>Valid token but {@code X-Branch-Id} not among the token's branches still granted → 403, with the
 *       code {@code BRANCH_FORBIDDEN} so a client can tell a lost branch from a missing permission.</li>
 *   <li>No token → request continues unauthenticated (protected routes then 401).</li>
 * </ul>
 *
 * <p>{@code /auth/me}, {@code /auth/refresh} and {@code /auth/logout} act in no branch, so they ignore
 * {@code X-Branch-Id}: a user who loses the branch they are working in can still read who they are,
 * refresh and sign out, and pick another branch.
 *
 * <p>Authorities are what the user's role at the active branch permits, plus the
 * company-level permissions they hold at any branch; a request naming no branch
 * gets every branch's (ADR-0007). It can reach no branch's data that way: every
 * branch-scoped operation requires the header.
 *
 * <p>The token's company is the request's tenant ({@code TenantContext}). Every request,
 * authenticated or not, ends with no tenant on the thread, and one that arrives carrying a
 * scope left by earlier work has it cleared first (ADR-0008).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String BRANCH_HEADER = "X-Branch-Id";
    private static final String BEARER = "Bearer ";
    private static final Set<String> BRANCHLESS_PATHS = Set.of(
            ApiVersions.V1 + "/auth/me", ApiVersions.V1 + "/auth/refresh", ApiVersions.V1 + "/auth/logout");
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

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
        // A container thread never brings a tenant in, so one that does carries a scope leaked by
        // earlier work; an anonymous request would otherwise read and stamp rows as that company.
        if (TenantContext.current().isPresent() || TenantContext.isSystem()) {
            log.warn("A tenant scope was left on the request thread; cleared before {} {}",
                    request.getMethod(), request.getRequestURI());
            TenantContext.clear();
        }
        try {
            filterAsTokenTenant(request, response, filterChain);
        } finally {
            TenantContext.clear();
        }
    }

    private void filterAsTokenTenant(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            filterChain.doFilter(request, response);
            return;
        }

        AccessClaims claims;
        try {
            claims = jwtService.parseAccessToken(header.substring(BEARER.length()));
            // set first: every lookup below, and the whole request after it, reads only this company's rows.
            // A token naming no real company (one of the persistence identifiers) is as bad as a forged one.
            TenantContext.startRequest(claims.companyId());
        } catch (Exception ex) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token");
            return;
        }

        try {
            // the branch the request acts in, if it names one
            UUID activeBranch = null;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String branchHeader = BRANCHLESS_PATHS.contains(path) ? null : request.getHeader(BRANCH_HEADER);
            if (branchHeader != null && !branchHeader.isBlank()) {
                try {
                    activeBranch = UUID.fromString(branchHeader.trim());
                } catch (IllegalArgumentException ex) {
                    writeError(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed " + BRANCH_HEADER);
                    return;
                }
            }

            Optional<Grants> grants = grantCache.current(claims.userId(), claims.companyId());
            if (grants.isPresent() && mayBeNewer(claims, grants.get(), activeBranch)) {
                grants = grantCache.reload(claims.userId(), claims.companyId());
            }
            if (grants.isEmpty() || grants.get().authVersion() != claims.authVersion()) {
                writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Access has changed since this token was issued");
                return;
            }

            // the token's branch roles count only while still granted: a revoke or a closed branch ends them at once
            Map<UUID, UUID> current = grants.get().roleByBranch();
            Map<UUID, UUID> roleByBranch = new HashMap<>();
            claims.roleByBranch().forEach((branchId, roleId) -> {
                if (roleId.equals(current.get(branchId))) {
                    roleByBranch.put(branchId, roleId);
                }
            });

            if (activeBranch != null) {
                UUID named = claims.roleByBranch().get(activeBranch);
                if (named != null && current.containsKey(activeBranch) && !named.equals(current.get(activeBranch))) {
                    writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                            "Your role at branch " + activeBranch + " changed");
                    return;
                }
                if (!roleByBranch.containsKey(activeBranch)) {
                    writeError(response, HttpStatus.FORBIDDEN, "BRANCH_FORBIDDEN",
                            "No access to branch " + activeBranch);
                    return;
                }
                BranchContext.set(activeBranch);
            }

            SecurityContextHolder.getContext().setAuthentication(authenticate(claims, roleByBranch, activeBranch));

            filterChain.doFilter(request, response);
        } finally {
            BranchContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Whether the token may reflect a change this instance's copy of the user predates; only another instance
     * can have made one, and then the copy is read afresh before the token is judged by it. So: a higher auth
     * version, or a role at the requested branch the copy does not hold, in a token issued after the copy was
     * read (iat counts whole seconds, hence the second's slack). An older token never makes it read again.
     */
    private static boolean mayBeNewer(AccessClaims claims, Grants grants, UUID activeBranch) {
        if (claims.authVersion() > grants.authVersion()) {
            return true;
        }
        UUID named = activeBranch == null ? null : claims.roleByBranch().get(activeBranch);
        return named != null && !named.equals(grants.roleByBranch().get(activeBranch))
                && claims.issuedAt() != null && claims.issuedAt().plusSeconds(1).isAfter(grants.readAt());
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

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":%d,\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(status.value(), code, status.getReasonPhrase(), detail));
    }
}
