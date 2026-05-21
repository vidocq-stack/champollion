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

    // APT @ProtobufStatic : activé par javac via ServiceLoader.
    provides javax.annotation.processing.Processor
            with io.vidocq.champollion.protobuf.codegen.ProtobufStaticProcessor;
}
