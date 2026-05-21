package io.vidocq.champollion.protobuf;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modèle réflexif des types Protocol Buffers (alignement mental sur
 * {@code com.google.protobuf.Descriptors}).
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#file-descriptor-set">descriptor.proto</a>.</p>
 *
 * <p>M1.4 — data model + synthèse via {@link #forRecord(Class)} sur des records
 * annotés {@link ProtobufMessage}. Le bootstrap auto-décrivant
 * ({@code descriptor.proto} comme {@code FileDescriptorProto}) viendra avec
 * le codegen M2.</p>
 */
public final class Descriptors {

    private static final ConcurrentHashMap<Class<?>, Descriptor> SYNTHESIZED = new ConcurrentHashMap<>();

    private Descriptors() {}

    /**
     * Synthétise un {@link Descriptor} à partir d'un record annoté
     * {@link ProtobufMessage} ; identique à la résolution faite par le runtime
     * binding mais exposée comme méta-information stable, JSON-friendly.
     */
    public static Descriptor forRecord(Class<?> recordClass) {
        Objects.requireNonNull(recordClass, "recordClass");
        return SYNTHESIZED.computeIfAbsent(recordClass, Descriptors::synthesize);
    }

    private static Descriptor synthesize(Class<?> recordClass) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass + " is not a record");
        }
        ProtobufMessage pm = recordClass.getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(recordClass + " is missing @ProtobufMessage");
        }
        String simpleName = pm.value().isEmpty() ? recordClass.getSimpleName() : pm.value();
        String fullName = recordClass.getPackageName().isEmpty()
                ? simpleName
                : recordClass.getPackageName() + "." + simpleName;

        List<FieldDescriptor> fields = new ArrayList<>();
        for (RecordComponent rc : recordClass.getRecordComponents()) {
            ProtobufField pf = rc.getAnnotation(ProtobufField.class);
            if (pf == null) continue;
            boolean repeated = List.class.isAssignableFrom(rc.getType());
            Cardinality card = repeated ? Cardinality.REPEATED : Cardinality.IMPLICIT;
            Class<?> element = repeated ? resolveListElement(rc) : rc.getType();
            String messageTypeName = pf.type() == FieldType.MESSAGE ? element.getName() : null;
            String enumTypeName = pf.type() == FieldType.ENUM ? element.getName() : null;
            fields.add(new FieldDescriptor(
                    rc.getName(),
                    toJsonName(rc.getName()),
                    pf.number(),
                    pf.type(),
                    card,
                    pf.packed() && pf.type().packable(),
                    messageTypeName,
                    enumTypeName));
        }
        fields.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return new Descriptor(simpleName, fullName, List.copyOf(fields), List.of(), List.of());
    }

    private static Class<?> resolveListElement(RecordComponent rc) {
        Type generic = rc.getGenericType();
        if (generic instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) return c;
        }
        throw new IllegalArgumentException(
                "Cannot resolve List element type for " + rc.getName());
    }

    /**
     * {@code under_score → underScore} (Proto3 JSON Canonical Mapping §JsonName).
     */
    public static String toJsonName(String protoName) {
        StringBuilder sb = new StringBuilder(protoName.length());
        boolean upperNext = false;
        for (int i = 0; i < protoName.length(); i++) {
            char c = protoName.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ================================================================== Data classes

    public enum Syntax { PROTO2, PROTO3, EDITION_2023 }

    /**
     * Cardinalité — {@code IMPLICIT} = proto3 default (pas de présence
     * explicite), {@code EXPLICIT} = proto2 / Editions {@code field_presence=EXPLICIT},
     * {@code REPEATED} = liste, {@code REQUIRED} = legacy proto2.
     */
    public enum Cardinality { IMPLICIT, EXPLICIT, REPEATED, REQUIRED }

    public record FileDescriptor(
            String name,
            String packageName,
            Syntax syntax,
            List<Descriptor> messageTypes,
            List<EnumDescriptor> enumTypes) {

        public FileDescriptor {
            Objects.requireNonNull(name, "name");
            packageName = packageName == null ? "" : packageName;
            syntax = syntax == null ? Syntax.PROTO3 : syntax;
            messageTypes = List.copyOf(messageTypes);
            enumTypes = List.copyOf(enumTypes);
        }

        public Descriptor findMessageType(String name) {
            for (Descriptor d : messageTypes) {
                if (d.name().equals(name) || d.fullName().equals(name)) return d;
            }
            return null;
        }
    }

    public record Descriptor(
            String name,
            String fullName,
            List<FieldDescriptor> fields,
            List<Descriptor> nestedMessageTypes,
            List<EnumDescriptor> nestedEnumTypes) {

        public Descriptor {
            fields = List.copyOf(fields);
            nestedMessageTypes = List.copyOf(nestedMessageTypes);
            nestedEnumTypes = List.copyOf(nestedEnumTypes);
        }

        public FieldDescriptor findFieldByNumber(int number) {
            for (FieldDescriptor fd : fields) {
                if (fd.number() == number) return fd;
            }
            return null;
        }

        public FieldDescriptor findFieldByName(String name) {
            for (FieldDescriptor fd : fields) {
                if (fd.name().equals(name)) return fd;
            }
            return null;
        }

        public FieldDescriptor findFieldByJsonName(String jsonName) {
            for (FieldDescriptor fd : fields) {
                if (fd.jsonName().equals(jsonName)) return fd;
            }
            return null;
        }

        public Map<Integer, FieldDescriptor> fieldsByNumber() {
            Map<Integer, FieldDescriptor> map = new LinkedHashMap<>();
            for (FieldDescriptor fd : fields) map.put(fd.number(), fd);
            return Collections.unmodifiableMap(map);
        }
    }

    public record FieldDescriptor(
            String name,
            String jsonName,
            int number,
            FieldType type,
            Cardinality cardinality,
            boolean packed,
            String messageTypeName,
            String enumTypeName) {

        public FieldDescriptor {
            if (number < WireFormat.FIRST_FIELD_NUMBER || number > WireFormat.MAX_FIELD_NUMBER) {
                throw new IllegalArgumentException("Invalid field number: " + number);
            }
            Objects.requireNonNull(type, "type");
            cardinality = cardinality == null ? Cardinality.IMPLICIT : cardinality;
            // jsonName auto-derived si null
            jsonName = jsonName == null ? toJsonName(name) : jsonName;
        }

        public boolean isRepeated() {
            return cardinality == Cardinality.REPEATED;
        }
    }

    public record EnumDescriptor(
            String name,
            String fullName,
            List<EnumValueDescriptor> values) {

        public EnumDescriptor {
            values = List.copyOf(values);
        }

        public EnumValueDescriptor findValueByNumber(int number) {
            for (EnumValueDescriptor v : values) {
                if (v.number() == number) return v;
            }
            return null;
        }
    }

    public record EnumValueDescriptor(String name, int number) {}
}
