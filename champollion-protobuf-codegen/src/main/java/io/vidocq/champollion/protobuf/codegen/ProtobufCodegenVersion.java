package io.vidocq.champollion.protobuf.codegen;

/**
 * Marqueur de version du module codegen — placeholder M1 garantissant que le
 * package n'est pas vide pour le compilateur Java.
 *
 * <p>Le codegen réel ({@code .proto → .java} et APT {@code @ProtobufStatic})
 * est implémenté en M2/M3.</p>
 */
public final class ProtobufCodegenVersion {

    public static final String JALON = "M1-skeleton";

    private ProtobufCodegenVersion() {}
}
