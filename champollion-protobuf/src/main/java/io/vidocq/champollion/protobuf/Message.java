package io.vidocq.champollion.protobuf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Marker pour les types sérialisables en Protocol Buffers.
 *
 * <p>Les méthodes par défaut délèguent à {@link Protobuf} qui résout
 * (cache concurrent) un {@code BindingPlan} via réflexion sur les
 * {@link ProtobufField} des record components.</p>
 *
 * <p>Records annotés {@link ProtobufMessage} : implémenter {@code Message}
 * n'est pas obligatoire — {@link Protobuf#toByteArray(Object)} fonctionne
 * aussi sur des records non-implémentants — mais c'est l'idiome recommandé
 * pour bénéficier des méthodes par défaut.</p>
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
