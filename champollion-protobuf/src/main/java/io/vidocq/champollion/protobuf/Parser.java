package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.io.InputStream;

/**
 * Deserializes Protocol Buffers bytes into an instance of {@code T}.
 *
 * <p>Implementations are obtained via {@link Protobuf#parser(Class)} (reflective
 * runtime resolution, cached by {@link Class}) or via the static codegen
 * {@code champollion-protobuf-codegen} (loaded via {@link java.util.ServiceLoader}).</p>
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
