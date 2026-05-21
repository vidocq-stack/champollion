package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque une méthode d'une interface {@link ProtobufService} comme RPC
 * Protocol Buffers.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#services">Proto3 §Services</a>.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProtobufRpc {

    /** Nom de la méthode tel que vu sur le wire. Vide = nom Java. */
    String value() default "";

    boolean clientStreaming() default false;
    boolean serverStreaming() default false;
}
