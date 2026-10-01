package vn.essvn.erpcafe.common.exception;

/**
 * Class: ForbiddenException
 * Purpose: Signals that an authenticated caller may not perform this action, rendered as HTTP 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
