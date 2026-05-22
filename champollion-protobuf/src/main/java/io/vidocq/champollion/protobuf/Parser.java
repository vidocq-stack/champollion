package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.io.InputStream;

/**
 * Désérialise des octets Protocol Buffers en une instance de {@code T}.
 *
 * <p>Implémentations obtenues via {@link Protobuf#parser(Class)} (résolution
 * runtime reflectif, cachée par {@link Class}) ou via le codegen statique
 * {@code champollion-protobuf-codegen} (chargé via {@link java.util.ServiceLoader}).</p>
 */
public interface Parser<T> {

    T parseFrom(CodedInputStream in) throws IOException;

    default T parseFrom(byte[] data) throws IOException {
        return parseFrom(CodedInputStream.newInstance(data));
    }

    default T parseFrom(byte[] data, int offset, int length) throws IOException {
        return parseFrom(CodedInputStream.newInstance(data, offset, length));
    }

    default T parseFrom(InputStream in) throws IOException {
        return parseFrom(CodedInputStream.newInstance(in));
    }
}
