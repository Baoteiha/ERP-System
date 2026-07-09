package vn.essvn.erpcafe.common.exception;

/** Thrown on a state/uniqueness conflict (e.g. duplicate code). Maps to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
