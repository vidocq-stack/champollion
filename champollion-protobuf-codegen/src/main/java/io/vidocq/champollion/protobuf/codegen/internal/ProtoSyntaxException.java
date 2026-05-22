package io.vidocq.champollion.protobuf.codegen.internal;

/**
 * Erreur de syntaxe lors du parsing d'un fichier {@code .proto}.
 */
public final class ProtoSyntaxException extends RuntimeException {
    public ProtoSyntaxException(String message) {
        super(message);
    }
}
