/**
 * Codegen Protobuf — squelette M1, implémentation réelle reportée à M2/M3.
 *
 * <p>M2 : compilateur {@code .proto → .java} (lexer + parser + emitter Class-File API)
 * invoqué par {@code champollion-protobuf-maven-plugin}.</p>
 *
 * <p>M3 : APT {@code @ProtobufStatic} sur records Java pour produire un
 * {@code <FQN>$$Binding} compilé sans réflexion, ServiceLoader-discoverable,
 * compatible AOT (GraalVM, Leyden CDS).</p>
 */
package io.vidocq.champollion.protobuf.codegen;
