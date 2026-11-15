package org.yanoproject.app.api;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Marks routes that need a Cardano runtime rather than only the application host. */
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RequiresCardano { }
