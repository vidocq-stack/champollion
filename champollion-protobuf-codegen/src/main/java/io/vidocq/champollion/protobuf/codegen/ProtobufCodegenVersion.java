package io.vidocq.champollion.protobuf.codegen;

/**
 * Codegen module version marker — M1 placeholder ensuring the
 * package is not empty for the Java compiler.
 *
 * <p>The real codegen ({@code .proto → .java} and APT {@code @ProtobufStatic})
 * is implemented in M2/M3.</p>
 */
public final class ProtobufCodegenVersion {

    public static final String JALON = "M1-skeleton";

    private ProtobufCodegenVersion() {}
}
