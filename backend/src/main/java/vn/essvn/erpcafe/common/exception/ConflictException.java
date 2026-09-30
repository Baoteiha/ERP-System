package vn.essvn.erpcafe.common.exception;

/** Thrown on a state/uniqueness conflict (e.g. duplicate code). Maps to HTTP 409. */
public class ConflictException extends RuntimeException {

    /** Shared by every stale-form (version mismatch) conflict so clients see one message. */
    public static final String STALE_EDIT_MESSAGE =
            "This record was changed by someone else. Reload and try again.";

    public ConflictException(String message) {
        super(message);
    }
}
