/**
 * Codegen Protobuf : APT {@code @ProtobufStatic} + parseur {@code .proto}.
 *
 * <p>Squelette M1 ; implémentations réelles en M2 ({@code .proto → Java} via
 * Class-File API) et M3 (mode statique sur records annotés).</p>
 */
module io.vidocq.champollion.protobuf.codegen {
    requires java.compiler;
    requires io.vidocq.champollion.protobuf;

    exports io.vidocq.champollion.protobuf.codegen;

    // Activation du Processor déclarée en M3.
}
