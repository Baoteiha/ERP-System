package vn.essvn.erpcafe.common.exception;

/** Signals that a caller has exceeded a rate limit; rendered as HTTP 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
