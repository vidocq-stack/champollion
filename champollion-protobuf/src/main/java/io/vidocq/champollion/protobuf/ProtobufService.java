package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque une interface Java comme service Protocol Buffers / gRPC.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#services">Proto3 §Services</a>.</p>
 *
 * <p>Le runtime utilise les {@link ProtobufRpc} sur chaque méthode pour
 * récupérer le wire name, le type d'entrée et de sortie, et le mode
 * (unary vs streaming). chappe-grpc consomme ces métadonnées pour router
 * les RPC HTTP/2.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufService {

    /** FullName proto (ex. {@code "my.pkg.FooService"}). Vide = nom simple. */
    String value() default "";
}
