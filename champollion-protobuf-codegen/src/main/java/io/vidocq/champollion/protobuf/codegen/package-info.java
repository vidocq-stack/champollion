/**
 * Protobuf codegen — M1 skeleton, with the real implementation deferred to M2/M3.
 *
 * <p>M2: {@code .proto → .java} compiler (lexer + parser + Class-File API emitter)
 * invoked by {@code champollion-protobuf-maven-plugin}.</p>
 *
 * <p>M3: {@code @ProtobufStatic} APT on Java records to produce a compiled
 * {@code <FQN>$$Binding} without reflection, ServiceLoader-discoverable,
 * AOT-compatible (GraalVM, Leyden CDS).</p>
 */
package io.vidocq.champollion.protobuf.codegen;
