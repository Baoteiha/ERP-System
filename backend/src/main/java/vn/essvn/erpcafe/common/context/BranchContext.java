package vn.essvn.erpcafe.common.context;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the active branch for the current request. Backed by a thread-local so
 * services can resolve "which branch am I acting in" without threading it
 * through every method signature.
 *
 * <p>The value is set from the {@code X-Branch-Id} header by
 * {@code JwtAuthenticationFilter}, which first validates the branch against the
 * authenticated user's granted branch access (rejecting one they cannot reach)
 * and clears it once the request completes.
 */
public final class BranchContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private BranchContext() {
    }

    public static void set(UUID branchId) {
        CURRENT.set(branchId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID branchId = CURRENT.get();
        if (branchId == null) {
            throw new IllegalStateException("No active branch in context");
        }
        return branchId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
