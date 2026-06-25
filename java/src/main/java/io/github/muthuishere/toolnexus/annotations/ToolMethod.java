package io.github.muthuishere.toolnexus.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a tool. Vendor-neutral (the Spring-AI {@code @Tool} feel,
 * without depending on Spring).
 *
 * <p>The annotation is named {@code @ToolMethod} to avoid clashing with the
 * {@link io.github.muthuishere.toolnexus.Tool} interface, but is aliased as
 * {@code Tool} where convenient.
 *
 * <ul>
 *   <li>{@code name} — tool name; defaults to the method name.</li>
 *   <li>{@code description} — tool description.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMethod {
    String name() default "";

    String description() default "";
}
