package vn.essvn.erpcafe.common.context;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import vn.essvn.erpcafe.common.exception.UnauthorizedException;

/**
 * Class: TenantContext
 * Purpose: Holds the company the current thread acts for, or the system scope that may see every company.
 */
public final class TenantContext {

    /** A company, or the system scope ({@code companyId == null}). */
    private record Scope(UUID companyId) {
    }

    private static final Scope SYSTEM = new Scope(null);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID companyId) {
        CURRENT.set(new Scope(Objects.requireNonNull(companyId, "companyId")));
    }

    /** The company in force; empty with no tenant and in the system scope, which acts for no one company. */
    public static Optional<UUID> current() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.companyId());
    }

    public static UUID require() {
        return current().orElseThrow(() -> new UnauthorizedException("No tenant for this request"));
    }

    /**
     * The company a new tenant-owned row belongs to: the one in force. Outside a company (the
     * system scope, or no tenant at all) there is none, and the row is refused rather than
     * written under an identifier that is no company's.
     */
    public static UUID companyForNewRow() {
        return current().orElseThrow(() -> new IllegalStateException(
                "A tenant-owned row can only be written inside its company's tenant"));
    }

    public static boolean isSystem() {
        return CURRENT.get() == SYSTEM;
    }

    /**
     * Runs work that must see every company: login before the user (and so the tenant) is known,
     * startup seeding. A persistence session takes its tenant when it opens, so the work has to
     * open its own transaction; one already running keeps the tenant it began with.
     */
    public static <T> T runAsSystem(Supplier<T> work) {
        return runIn(SYSTEM, work);
    }

    public static void runAsSystem(Runnable work) {
        runIn(SYSTEM, asSupplier(work));
    }

    /** Runs work as one company, as a request of that company would; used to seed a new company's rows. */
    public static <T> T runAs(UUID companyId, Supplier<T> work) {
        return runIn(new Scope(Objects.requireNonNull(companyId, "companyId")), work);
    }

    public static void runAs(UUID companyId, Runnable work) {
        runAs(companyId, asSupplier(work));
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static Supplier<Void> asSupplier(Runnable work) {
        return () -> {
            work.run();
            return null;
        };
    }

    private static <T> T runIn(Scope scope, Supplier<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
