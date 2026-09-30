package vn.essvn.erpcafe.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Class: MaxUtf8Bytes
 * Purpose: Constrains a string's UTF-8 encoded length to at most {@code value()} bytes.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "must be at most {value} bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
