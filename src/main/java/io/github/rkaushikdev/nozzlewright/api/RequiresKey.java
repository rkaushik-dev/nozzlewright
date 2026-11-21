package io.github.rkaushikdev.nozzlewright.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route, or every route of a class, as needing a site key in the {@value
 * Services#API_KEY_HEADER} header on a gateway that has any keys configured.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresKey {}
