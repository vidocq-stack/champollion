package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un {@code record} (ou une classe immutable canonique) comme message
 * Protocol Buffers. Les record components doivent porter {@link ProtobufField}.
 *
 * <p>L'annotation seule suffit en mode runtime reflectif (introspection via
 * {@code MethodHandles}). Le mode statique (codegen via {@code champollion-protobuf-codegen})
 * pourra plus tard produire un binding compilé sans réflexion.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufMessage {

    /** Nom du message (par défaut, le simple name de la classe). */
    String value() default "";
}
