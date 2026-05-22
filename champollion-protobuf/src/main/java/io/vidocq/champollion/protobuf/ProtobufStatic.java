package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indique au codegen statique ({@code champollion-protobuf-codegen} APT) qu'un
 * {@code Parser<T>} dédié, sans réflexion, doit être généré pour ce type.
 *
 * <p>Le record doit aussi porter {@link ProtobufMessage} et ses champs
 * {@link ProtobufField}. Le parser généré est enregistré via
 * {@link java.util.ServiceLoader} comme un {@link ParserProvider} — au runtime
 * {@code Protobuf.parser(Class)} préférera ce parser au runtime reflectif.</p>
 *
 * <p>Avantage AOT (GraalVM, Leyden CDS) : aucun {@code MethodHandles},
 * aucune introspection — toute la résolution est faite à la compilation.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufStatic {}
