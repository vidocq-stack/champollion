package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a {@link ProtobufService} interface as a Protocol Buffers RPC.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#services">Proto3 §Services</a>.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProtobufRpc {

    /** Method name as seen on the wire. Empty = Java name. */
    String value() default "";

    boolean clientStreaming() default false;
    boolean serverStreaming() default false;
}
