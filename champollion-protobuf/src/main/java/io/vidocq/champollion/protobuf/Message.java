package io.vidocq.champollion.protobuf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Marker for types serializable as Protocol Buffers.
 *
 * <p>The default methods delegate to {@link Protobuf}, which resolves
 * (via a concurrent cache) a {@code BindingPlan} through reflection on the
 * {@link ProtobufField} annotations of the record components.</p>
 *
 * <p>For records annotated {@link ProtobufMessage}, implementing {@code Message}
 * is not mandatory — {@link Protobuf#toByteArray(Object)} also works on records
 * that do not implement it — but this is the recommended idiom to benefit from
 * the default methods.</p>
 */
public interface Message {

    default byte[] toByteArray() {
        return Protobuf.toByteArray(this);
    }

    default int getSerializedSize() {
        return Protobuf.getSerializedSize(this);
    }

    default void writeTo(OutputStream out) throws IOException {
        Protobuf.writeTo(this, out);
    }

    default void writeTo(CodedOutputStream out) throws IOException {
        Protobuf.writeTo(this, out);
    }

    @SuppressWarnings("unchecked")
    static <T> T parseFrom(Class<T> type, byte[] data) {
        try {
            return (T) Protobuf.parser(type).parseFrom(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
