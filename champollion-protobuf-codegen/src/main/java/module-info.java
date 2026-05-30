/**
 * Protobuf codegen: {@code @ProtobufStatic} APT + {@code .proto} parser.
 *
 * <p>M1 skeleton; real implementations in M2 ({@code .proto → Java} via
 * Class-File API) and M3 (static mode on annotated records).</p>
 */
module io.vidocq.champollion.protobuf.codegen {
    requires java.compiler;
    requires io.vidocq.champollion.protobuf;

    exports io.vidocq.champollion.protobuf.codegen;
    exports io.vidocq.champollion.protobuf.codegen.cli;

    // @ProtobufStatic APT: activated by javac via ServiceLoader.
    provides javax.annotation.processing.Processor
            with io.vidocq.champollion.protobuf.codegen.ProtobufStaticProcessor;
}
