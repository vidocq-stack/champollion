/**
 * Implémentation Protocol Buffers (proto3 wire binaire + canonical JSON mapping).
 *
 * <p>Le contrat avec chappe reste byte-level : un {@code GrpcHandler} appelle
 * {@code Parser.parseFrom(call.receive())} et {@code call.send(message.toByteArray())}.
 * Aucune API chappe n'est implémentée ici.</p>
 *
 * <p>Edition 2023 features et codegen depuis {@code .proto} sont traités dans
 * {@code champollion-protobuf-codegen} et {@code champollion-protobuf-maven-plugin}.</p>
 */
module io.vidocq.champollion.protobuf {
    requires transitive io.vidocq.champollion.jsonp;

    exports io.vidocq.champollion.protobuf;
    exports io.vidocq.champollion.protobuf.wkt;

    // SPI ServiceLoader pour les parsers statiques générés par l'APT.
    uses io.vidocq.champollion.protobuf.ParserProvider;
}
