package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code google.protobuf.Any} — enveloppe pour un message arbitraire.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#any">Any</a>.</p>
 *
 * <p>Wire format : {@code string type_url = 1; bytes value = 2;}</p>
 *
 * <p>Méthodes utiles :</p>
 * <ul>
 *   <li>{@link #pack(Object)} — empaquette un message annoté
 *       {@link ProtobufMessage} avec le {@code type_url} par défaut
 *       ({@code type.googleapis.com/<fullName>}).</li>
 *   <li>{@link #unpack(Class)} — désérialise vers une classe attendue ;
 *       vérifie que le {@code type_url} matche.</li>
 *   <li>{@link #unpack()} — désérialise en consultant {@link TypeRegistry}.</li>
 * </ul>
 *
 * <p>Le JSON canonical d'Any (aplatissement des champs vs {@code "value"} pour
 * les WKT) sera traité dans un commit ultérieur — pour l'instant le mapping
 * tombe sur le défaut "objet avec type_url + value base64".</p>
 */
@ProtobufMessage("google.protobuf.Any")
public record Any(
        @ProtobufField(number = 1, type = FieldType.STRING) String type_url,
        @ProtobufField(number = 2, type = FieldType.BYTES) byte[] value
) implements Message {

    public static Any pack(Object message) {
        Objects.requireNonNull(message, "message");
        return pack(message, TypeRegistry.DEFAULT_BASE);
    }

    public static Any pack(Object message, String typeUrlBase) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(typeUrlBase, "typeUrlBase");
        ProtobufMessage pm = message.getClass().getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(
                    message.getClass() + " is missing @ProtobufMessage — cannot derive type_url");
        }
        String fullName = pm.value().isEmpty() ? message.getClass().getSimpleName() : pm.value();
        String url = typeUrlBase + "/" + fullName;
        byte[] bytes = Protobuf.toByteArray(message);
        return new Any(url, bytes);
    }

    /** Désérialise dans la classe attendue ; lève si le {@code type_url} ne matche pas. */
    public <T> T unpack(Class<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        String expected = TypeRegistry.fullNameFromTypeUrl(type_url);
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        String full = pm == null || pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        if (!expected.equals(full)) {
            throw new IllegalStateException(
                    "Any type_url '" + type_url + "' does not match expected " + full);
        }
        return Protobuf.parser(type).parseFrom(value);
    }

    /**
     * Désérialise en consultant {@link TypeRegistry}. Retourne {@code null}
     * si le type {@code type_url} n'est pas enregistré.
     */
    public Object unpack() throws IOException {
        String full = TypeRegistry.fullNameFromTypeUrl(type_url);
        Class<?> type = TypeRegistry.lookup(full);
        if (type == null) return null;
        return Protobuf.parser(type).parseFrom(value);
    }

    public boolean isA(Class<?> type) {
        String expected = TypeRegistry.fullNameFromTypeUrl(type_url);
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        String full = pm == null || pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        return expected.equals(full);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Any a
                && Objects.equals(type_url, a.type_url)
                && java.util.Arrays.equals(value, a.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type_url) ^ java.util.Arrays.hashCode(value);
    }
}
