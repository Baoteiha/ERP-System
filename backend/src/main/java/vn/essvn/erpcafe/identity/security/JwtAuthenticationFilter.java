package vn.essvn.erpcafe.identity.security;

import java.io.IOException;
import java.util.List;
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

/**
 * Authenticates each request from its Bearer access token and establishes the
 * active branch from the {@code X-Branch-Id} header.
 *
 * <ul>
 *   <li>Invalid/expired token → 401.</li>
 *   <li>Valid token but {@code X-Branch-Id} not in the user's accessible set → 403.</li>
 *   <li>No token → request continues unauthenticated (protected routes then 401).</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String BRANCH_HEADER = "X-Branch-Id";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthPrincipal principal;
        try {
            principal = jwtService.parseAccessToken(header.substring(BEARER.length()));
        } catch (Exception ex) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        try {
            // Resolve the active branch, if one was requested.
            String branchHeader = request.getHeader(BRANCH_HEADER);
            if (branchHeader != null && !branchHeader.isBlank()) {
                UUID branchId;
                try {
                    branchId = UUID.fromString(branchHeader.trim());
                } catch (IllegalArgumentException ex) {
                    writeError(response, HttpStatus.BAD_REQUEST, "Malformed " + BRANCH_HEADER);
                    return;
                }
                if (!principal.canAccessBranch(branchId)) {
                    writeError(response, HttpStatus.FORBIDDEN, "No access to branch " + branchId);
                    return;
                }
                BranchContext.set(branchId);
            }

            List<SimpleGrantedAuthority> authorities = buildAuthorities(principal);
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } finally {
            BranchContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private List<SimpleGrantedAuthority> buildAuthorities(AuthPrincipal principal) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return authorities;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(status.value(), status.getReasonPhrase(), detail));
    }
}
