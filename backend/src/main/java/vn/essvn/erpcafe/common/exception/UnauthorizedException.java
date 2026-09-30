package vn.essvn.erpcafe.common.exception;

/** Thrown when the caller is not authenticated or presents bad credentials. Maps to HTTP 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
