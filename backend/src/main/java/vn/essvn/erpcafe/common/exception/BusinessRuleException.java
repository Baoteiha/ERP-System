package vn.essvn.erpcafe.common.exception;

/** Thrown when a business rule / invariant is violated. Maps to HTTP 422. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
