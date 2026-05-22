package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registre des types acceptés par {@link Any#unpack(Class)} et par le mapping
 * JSON canonical d'{@code Any}.
 *
 * <p>Convention proto : un {@code type_url} a la forme
 * {@code "<base>/<full.name>"}, le {@code base} typique étant
 * {@code "type.googleapis.com"} (cf.
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#any">Any spec</a>).
 * Seule la partie {@code full.name} est utilisée pour le lookup ici — la base
 * est facultative pour les besoins du runtime.</p>
 *
 * <p>Mutable, thread-safe, statique au module. L'utilisateur enregistre ses
 * types via {@link #register(Class)} (le {@code fullName} provient de
 * {@link ProtobufMessage#value()}) ou directement par {@code typeFullName}.</p>
 */
public final class TypeRegistry {

    public static final String DEFAULT_BASE = "type.googleapis.com";

    private static final ConcurrentMap<String, Class<?>> BY_FULL_NAME = new ConcurrentHashMap<>();

    static {
        // Enregistrement automatique des WKT exposés par champollion.
        register(Timestamp.class);
        register(Duration.class);
        register(Empty.class);
        register(FieldMask.class);
        register(Struct.class);
        register(Value.class);
        register(ListValue.class);
        register(Wrappers.DoubleValue.class);
        register(Wrappers.FloatValue.class);
        register(Wrappers.Int64Value.class);
        register(Wrappers.UInt64Value.class);
        register(Wrappers.Int32Value.class);
        register(Wrappers.UInt32Value.class);
        register(Wrappers.BoolValue.class);
        register(Wrappers.StringValue.class);
        register(Wrappers.BytesValue.class);
    }

    private TypeRegistry() {}

    public static void register(Class<?> type) {
        Objects.requireNonNull(type, "type");
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(type + " is missing @ProtobufMessage");
        }
        String full = pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        BY_FULL_NAME.put(full, type);
    }

    public static void register(String typeFullName, Class<?> type) {
        Objects.requireNonNull(typeFullName, "typeFullName");
        Objects.requireNonNull(type, "type");
        BY_FULL_NAME.put(typeFullName, type);
    }

    public static Class<?> lookup(String typeFullName) {
        return BY_FULL_NAME.get(typeFullName);
    }

    /** Extrait la portion {@code <full.name>} d'un {@code type_url}. */
    public static String fullNameFromTypeUrl(String typeUrl) {
        int slash = typeUrl.lastIndexOf('/');
        return slash < 0 ? typeUrl : typeUrl.substring(slash + 1);
    }

    public static String typeUrlFor(Class<?> type) {
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(type + " is missing @ProtobufMessage");
        }
        String full = pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        return DEFAULT_BASE + "/" + full;
    }
}
