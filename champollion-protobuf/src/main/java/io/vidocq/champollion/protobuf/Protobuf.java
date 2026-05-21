package io.vidocq.champollion.protobuf;

import io.vidocq.champollion.protobuf.internal.RuntimeBinding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Point d'entrée pour la sérialisation et la désérialisation Protocol Buffers.
 *
 * <p>API stable, JPMS-friendly. Les implémentations runtime sont fournies par
 * {@link RuntimeBinding} (introspection reflective + cache par {@link Class}).
 * Le mode statique (codegen via {@code champollion-protobuf-codegen}) sera
 * exposé via {@link java.util.ServiceLoader} en M3.</p>
 *
 * <p>Exemple :</p>
 * <pre>{@code
 * @ProtobufMessage
 * record Person(@ProtobufField(number = 1, type = FieldType.STRING) String name,
 *               @ProtobufField(number = 2, type = FieldType.INT32) int age) {}
 *
 * byte[] bytes = Protobuf.toByteArray(new Person("alice", 30));
 * Person back  = Protobuf.parser(Person.class).parseFrom(bytes);
 * }</pre>
 */
public final class Protobuf {

    private Protobuf() {}

    public static <T> Parser<T> parser(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return RuntimeBinding.parser(type);
    }

    public static byte[] toByteArray(Object message) {
        Objects.requireNonNull(message, "message");
        try {
            int size = getSerializedSize(message);
            byte[] buffer = new byte[size];
            CodedOutputStream out = CodedOutputStream.newInstance(buffer);
            RuntimeBinding.writeTo(message, out);
            out.flush();
            return buffer;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to serialize " + message.getClass(), e);
        }
    }

    public static void writeTo(Object message, OutputStream out) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(out, "out");
        CodedOutputStream cos = CodedOutputStream.newInstance(out);
        RuntimeBinding.writeTo(message, cos);
        cos.flush();
    }

    public static void writeTo(Object message, CodedOutputStream out) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(out, "out");
        RuntimeBinding.writeTo(message, out);
    }

    public static int getSerializedSize(Object message) {
        Objects.requireNonNull(message, "message");
        return RuntimeBinding.getSerializedSize(message);
    }
}
