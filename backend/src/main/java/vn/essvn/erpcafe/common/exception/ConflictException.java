package vn.essvn.erpcafe.common.exception;

/** Thrown on a state/uniqueness conflict (e.g. duplicate code). Maps to HTTP 409. */
public class ConflictException extends RuntimeException {

    /** Shared by every stale-form (version mismatch) conflict so clients see one message. */
    public static final String STALE_EDIT_MESSAGE =
            "This record was changed by someone else. Reload and try again.";

    public ConflictException(String message) {
        super(message);
    }

    /**
     * Stale-form guard: the client echoes the version it loaded. If the row has changed since,
     * reject with 409 instead of silently overwriting the newer edit. Enforced only when a
     * version is supplied (older clients stay backward-compatible).
     */
    public static void requireCurrentVersion(long current, Long expected) {
        if (expected != null && expected != current) {
            throw new ConflictException(STALE_EDIT_MESSAGE);
        }
    }
}
