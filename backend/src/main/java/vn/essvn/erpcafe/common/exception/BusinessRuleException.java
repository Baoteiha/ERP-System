package vn.essvn.erpcafe.common.exception;

import java.util.Map;

/**
 * Thrown when a business rule / invariant is violated. Maps to HTTP 422 with its {@code code}
 * ({@code BUSINESS_RULE} unless the rule names its own) and any extra properties a client can act on.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> properties;

    public BusinessRuleException(String message) {
        this("BUSINESS_RULE", message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public String getCode() {
        return code;
    }

    /** Added as-is to the error body, beside {@code code} and {@code detail}. */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
