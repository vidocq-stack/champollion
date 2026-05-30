package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tells the static codegen ({@code champollion-protobuf-codegen} APT) that a
 * dedicated {@code Parser<T>} without reflection must be generated for this type.
 *
 * <p>The record must also carry {@link ProtobufMessage} and its fields
 * {@link ProtobufField}. The generated parser is registered via
 * {@link java.util.ServiceLoader} as a {@link ParserProvider} — at runtime
 * {@code Protobuf.parser(Class)} will prefer this parser over the reflective runtime.</p>
 *
 * <p>AOT advantage (GraalVM, Leyden CDS): no {@code MethodHandles},
 * no introspection — all resolution is done at compile time.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufStatic {}
