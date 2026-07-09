package vn.essvn.erpcafe.common.context;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Populates {@link BranchContext} from the {@code X-Branch-Id} request header
 * and clears it after the request completes.
 *
 * <p>Phase 0: the header is trusted as-is. Phase 1 will validate it against the
 * authenticated user's {@code UserBranchAccess} and reject unauthorized branches.
 */
@Component
public class BranchContextFilter extends OncePerRequestFilter {

    public static final String BRANCH_HEADER = "X-Branch-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader(BRANCH_HEADER);
            if (header != null && !header.isBlank()) {
                try {
                    BranchContext.set(UUID.fromString(header.trim()));
                } catch (IllegalArgumentException ignored) {
                    // malformed header -> leave context empty; scoped reads will require() and fail clearly
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            BranchContext.clear();
        }
    }
}
