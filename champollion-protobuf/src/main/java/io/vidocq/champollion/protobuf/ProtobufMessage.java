package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code record} (or a canonical immutable class) as a Protocol
 * Buffers message. Record components must carry {@link ProtobufField}.
 *
 * <p>The annotation alone is enough in reflective runtime mode (introspection via
 * {@code MethodHandles}). The static mode (codegen via {@code champollion-protobuf-codegen})
 * may later produce a compiled binding without reflection.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufMessage {

    /** Message name (by default, the class simple name). */
    String value() default "";
}
