package vn.essvn.erpcafe.common.exception;

/** Thrown when the request itself is malformed or incomplete (e.g. a missing header). Maps to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
