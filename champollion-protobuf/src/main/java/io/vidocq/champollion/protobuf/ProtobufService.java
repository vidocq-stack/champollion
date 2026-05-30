package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java interface as a Protocol Buffers / gRPC service.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#services">Proto3 §Services</a>.</p>
 *
 * <p>The runtime uses {@link ProtobufRpc} on each method to retrieve the wire
 * name, the input and output types, and the mode (unary vs streaming).
 * chappe-grpc consumes these metadata to route HTTP/2 RPCs.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufService {

    /** Proto FullName (e.g. {@code "my.pkg.FooService"}). Empty = simple name. */
    String value() default "";
}
