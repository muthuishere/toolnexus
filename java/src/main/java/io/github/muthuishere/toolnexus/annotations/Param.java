package io.github.muthuishere.toolnexus.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a single tool parameter. Optional — when omitted, the parameter name
 * (requires javac {@code -parameters}) and inferred type are used, and the
 * parameter is required.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {
    String name() default "";

    String description() default "";

    boolean required() default true;
}
