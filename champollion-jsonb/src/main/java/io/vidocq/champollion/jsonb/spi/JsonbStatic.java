package io.vidocq.champollion.jsonb.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type for generation of a {@link JsonbBinding} at compile time by
 * {@code champollion-codegen-apt}.
 *
 * <p>The generated binding will be registered as a {@link JsonbBinding} service
 * via a {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}
 * file and therefore used lookup-first by {@code ChampollionJsonb}, with no
 * reflection at runtime.</p>
 *
 * <p>{@link RetentionPolicy#CLASS} retention: preserved in bytecode to allow a
 * possible post-compile scan by {@code champollion-codegen-maven-plugin} on
 * transitively annotated types, without requiring source code access.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JsonbStatic {
}
