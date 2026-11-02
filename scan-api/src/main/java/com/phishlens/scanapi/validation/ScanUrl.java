package com.phishlens.scanapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An http(s) URL that risk-model can score. The scheme is optional, as in features.py.
 * Null and blank values pass; pair with @NotBlank.
 */
@Documented
@Constraint(validatedBy = ScanUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ScanUrl {

    String message() default "url must be an http or https URL with a valid host";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
