package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.ProtobufField;

import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation du Proto3 JSON Canonical Mapping. Branchée sur Jakarta JSON-P
 * (fourni par {@code champollion-jsonp}) via {@code Json.createGenerator} et
 * {@code Json.createParser}.
 */
public final class ProtobufJsonRuntime {

    private static final ConcurrentHashMap<Class<?>, JsonBindingPlan> PLANS = new ConcurrentHashMap<>();

    private ProtobufJsonRuntime() {}

    // ============================================================ Write

    public static String toJsonString(Object message) {
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = Json.createGenerator(sw)) {
            writeMessage(planFor(message.getClass()), message, gen);
        }
        return sw.toString();
    }

    public static void toJson(Object message, Writer writer) {
        try (JsonGenerator gen = Json.createGenerator(writer)) {
            writeMessage(planFor(message.getClass()), message, gen);
        }
    }

    public static void toJson(Object message, OutputStream out) {
        try (JsonGenerator gen = Json.createGenerator(out)) {
            writeMessage(planFor(message.getClass()), message, gen);
        }
    }

    private static void writeMessage(JsonBindingPlan plan, Object message, JsonGenerator gen) {
        gen.writeStartObject();
        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            if (value == null) continue;
            if (fb.repeated) {
                List<?> list = (List<?>) value;
                if (list.isEmpty()) continue; // proto3 canonical : omet [] vide
                gen.writeStartArray(fb.jsonName);
                for (Object item : list) writeScalar(fb, item, gen, null);
                gen.writeEnd();
            } else if (isDefault(fb, value)) {
                continue; // proto3 implicit presence
            } else {
                writeScalar(fb, value, gen, fb.jsonName);
            }
        }
        gen.writeEnd();
    }

    private static void writeScalar(FieldBinding fb, Object value, JsonGenerator gen, String key) {
        switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> {
                int v = (int) value;
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> {
                // Proto3 canonical : int64 *toujours* en string.
                String v = Long.toString((long) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case BOOL -> {
                boolean v = (boolean) value;
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case FLOAT -> {
                float v = (float) value;
                if (Float.isNaN(v)) {
                    if (key != null) gen.write(key, "NaN"); else gen.write("NaN");
                } else if (v == Float.POSITIVE_INFINITY) {
                    if (key != null) gen.write(key, "Infinity"); else gen.write("Infinity");
                } else if (v == Float.NEGATIVE_INFINITY) {
                    if (key != null) gen.write(key, "-Infinity"); else gen.write("-Infinity");
                } else {
                    if (key != null) gen.write(key, v); else gen.write(v);
                }
            }
            case DOUBLE -> {
                double v = (double) value;
                if (Double.isNaN(v)) {
                    if (key != null) gen.write(key, "NaN"); else gen.write("NaN");
                } else if (v == Double.POSITIVE_INFINITY) {
                    if (key != null) gen.write(key, "Infinity"); else gen.write("Infinity");
                } else if (v == Double.NEGATIVE_INFINITY) {
                    if (key != null) gen.write(key, "-Infinity"); else gen.write("-Infinity");
                } else {
                    if (key != null) gen.write(key, v); else gen.write(v);
                }
            }
            case STRING -> {
                String v = (String) value;
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case BYTES -> {
                String v = Base64.getEncoder().encodeToString((byte[]) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case ENUM -> {
                String v = ((Enum<?>) value).name();
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case MESSAGE -> {
                if (key != null) gen.writeStartObject(key); else gen.writeStartObject();
                JsonBindingPlan nested = planFor(value.getClass());
                writeMessageBody(nested, value, gen);
                gen.writeEnd();
            }
        }
    }

    private static void writeMessageBody(JsonBindingPlan plan, Object message, JsonGenerator gen) {
        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            if (value == null) continue;
            if (fb.repeated) {
                List<?> list = (List<?>) value;
                if (list.isEmpty()) continue;
                gen.writeStartArray(fb.jsonName);
                for (Object item : list) writeScalar(fb, item, gen, null);
                gen.writeEnd();
            } else if (isDefault(fb, value)) {
                continue;
            } else {
                writeScalar(fb, value, gen, fb.jsonName);
            }
        }
    }

    // ============================================================ Read

    @SuppressWarnings("unchecked")
    public static <T> T fromJsonString(Class<T> type, String json) throws IOException {
        JsonBindingPlan plan = planFor(type);
        try (JsonParser parser = Json.createParser(new StringReader(json))) {
            if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
                throw new IOException("Expected JSON object for " + type.getSimpleName());
            }
            return (T) readMessage(plan, parser);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readMessage(JsonBindingPlan plan, JsonParser parser) throws IOException {
        Object[] slots = new Object[plan.componentCount];
        boolean[] hasValue = new boolean[plan.componentCount];

        while (parser.hasNext()) {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.END_OBJECT) break;
            if (e != JsonParser.Event.KEY_NAME) {
                throw new IOException("Expected key, got " + e);
            }
            String key = parser.getString();
            FieldBinding fb = plan.byJsonName.get(key);
            if (fb == null) fb = plan.byProtoName.get(key);
            JsonParser.Event v = parser.next();
            if (fb == null) {
                skipValue(parser, v);
                continue;
            }
            if (v == JsonParser.Event.VALUE_NULL) {
                continue; // canonical : null = absent
            }
            if (fb.repeated) {
                if (v != JsonParser.Event.START_ARRAY) {
                    throw new IOException("Expected array for repeated field " + key);
                }
                List list = new ArrayList<>();
                slots[fb.componentIndex] = list;
                hasValue[fb.componentIndex] = true;
                while (true) {
                    JsonParser.Event next = parser.next();
                    if (next == JsonParser.Event.END_ARRAY) break;
                    list.add(readScalar(fb, parser, next));
                }
            } else {
                slots[fb.componentIndex] = readScalar(fb, parser, v);
                hasValue[fb.componentIndex] = true;
            }
        }

        // Compléter les défauts proto3.
        for (int i = 0; i < slots.length; i++) {
            if (hasValue[i]) continue;
            FieldBinding fb = plan.byComponentIndex[i];
            slots[i] = defaultFor(fb);
        }
        try {
            return plan.constructor.invokeWithArguments(slots);
        } catch (Throwable t) {
            throw new IOException("Cannot invoke constructor of " + plan.recordType, t);
        }
    }

    private static Object readScalar(FieldBinding fb, JsonParser parser, JsonParser.Event v) throws IOException {
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> {
                if (v == JsonParser.Event.VALUE_NUMBER) yield parser.getInt();
                if (v == JsonParser.Event.VALUE_STRING) yield Integer.parseInt(parser.getString());
                throw new IOException("Expected int32-compatible JSON value, got " + v);
            }
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> {
                if (v == JsonParser.Event.VALUE_STRING) yield Long.parseLong(parser.getString());
                if (v == JsonParser.Event.VALUE_NUMBER) yield parser.getLong();
                throw new IOException("Expected int64-compatible JSON value, got " + v);
            }
            case BOOL -> {
                if (v == JsonParser.Event.VALUE_TRUE) yield true;
                if (v == JsonParser.Event.VALUE_FALSE) yield false;
                throw new IOException("Expected boolean, got " + v);
            }
            case FLOAT -> readFloating(parser, v).floatValue();
            case DOUBLE -> readFloating(parser, v).doubleValue();
            case STRING -> {
                if (v != JsonParser.Event.VALUE_STRING) {
                    throw new IOException("Expected string, got " + v);
                }
                yield parser.getString();
            }
            case BYTES -> {
                if (v != JsonParser.Event.VALUE_STRING) {
                    throw new IOException("Expected base64 string, got " + v);
                }
                yield Base64.getDecoder().decode(parser.getString());
            }
            case ENUM -> {
                Object[] constants = fb.elementType.getEnumConstants();
                if (v == JsonParser.Event.VALUE_STRING) {
                    String name = parser.getString();
                    for (Object c : constants) {
                        if (((Enum<?>) c).name().equals(name)) yield c;
                    }
                    throw new IOException("Unknown enum constant '" + name + "' for " + fb.elementType);
                }
                if (v == JsonParser.Event.VALUE_NUMBER) {
                    int ord = parser.getInt();
                    if (ord < 0 || ord >= constants.length) {
                        throw new IOException("Unknown enum ordinal " + ord + " for " + fb.elementType);
                    }
                    yield constants[ord];
                }
                throw new IOException("Expected enum value, got " + v);
            }
            case MESSAGE -> {
                if (v != JsonParser.Event.START_OBJECT) {
                    throw new IOException("Expected message object, got " + v);
                }
                yield readMessage(planFor(fb.elementType), parser);
            }
        };
    }

    private static Number readFloating(JsonParser parser, JsonParser.Event v) throws IOException {
        if (v == JsonParser.Event.VALUE_NUMBER) return parser.getBigDecimal();
        if (v == JsonParser.Event.VALUE_STRING) {
            String s = parser.getString();
            return switch (s) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(s);
            };
        }
        throw new IOException("Expected floating-point value, got " + v);
    }

    private static void skipValue(JsonParser parser, JsonParser.Event v) {
        if (v == JsonParser.Event.START_OBJECT || v == JsonParser.Event.START_ARRAY) {
            int depth = 1;
            while (parser.hasNext() && depth > 0) {
                JsonParser.Event next = parser.next();
                if (next == JsonParser.Event.START_OBJECT || next == JsonParser.Event.START_ARRAY) depth++;
                else if (next == JsonParser.Event.END_OBJECT || next == JsonParser.Event.END_ARRAY) depth--;
            }
        }
        // scalar : déjà consommé par parser.next()
    }

    // ============================================================ Plan

    private static boolean isDefault(FieldBinding fb, Object value) {
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> ((int) value) == 0;
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> ((long) value) == 0L;
            case BOOL -> !((boolean) value);
            case FLOAT -> ((float) value) == 0.0f;
            case DOUBLE -> ((double) value) == 0.0;
            case STRING -> ((String) value).isEmpty();
            case BYTES -> ((byte[]) value).length == 0;
            case ENUM -> ((Enum<?>) value).ordinal() == 0;
            case MESSAGE -> false;
        };
    }

    private static Object defaultFor(FieldBinding fb) {
        if (fb.repeated) return List.of();
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> 0;
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> 0L;
            case BOOL -> false;
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0;
            case STRING -> "";
            case BYTES -> new byte[0];
            case ENUM -> fb.elementType.getEnumConstants()[0];
            case MESSAGE -> null;
        };
    }

    private static JsonBindingPlan planFor(Class<?> type) {
        JsonBindingPlan cached = PLANS.get(type);
        if (cached != null) return cached;
        JsonBindingPlan computed = buildPlan(type);
        JsonBindingPlan existing = PLANS.putIfAbsent(type, computed);
        return existing != null ? existing : computed;
    }

    private static JsonBindingPlan buildPlan(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type + " is not a record");
        }
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] componentTypes = new Class<?>[components.length];
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access " + type + " — open package to io.vidocq.champollion.protobuf.", e);
        }
        List<FieldBinding> fields = new ArrayList<>();
        Map<String, FieldBinding> byJsonName = new HashMap<>();
        Map<String, FieldBinding> byProtoName = new HashMap<>();
        FieldBinding[] byComponentIndex = new FieldBinding[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            componentTypes[i] = rc.getType();
            ProtobufField pf = rc.getAnnotation(ProtobufField.class);
            if (pf == null) continue;
            boolean repeated = List.class.isAssignableFrom(rc.getType());
            Class<?> element = repeated ? resolveListElement(rc) : rc.getType();
            MethodHandle getter;
            try {
                getter = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot access accessor for " + rc.getName(), e);
            }
            String protoName = rc.getName();
            String jsonName = Descriptors.toJsonName(protoName);
            FieldBinding fb = new FieldBinding(
                    pf.number(), pf.type(), repeated, element, i,
                    getter, protoName, jsonName);
            fields.add(fb);
            byJsonName.put(jsonName, fb);
            byProtoName.put(protoName, fb);
            byComponentIndex[i] = fb;
        }
        fields.sort((a, b) -> Integer.compare(a.number, b.number));

        MethodHandle constructor;
        try {
            constructor = lookup.findConstructor(type, MethodType.methodType(void.class, componentTypes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("cannot find canonical ctor for " + type, e);
        }

        return new JsonBindingPlan(type, fields, byJsonName, byProtoName, byComponentIndex,
                constructor, components.length);
    }

    private static Class<?> resolveListElement(RecordComponent rc) {
        Type generic = rc.getGenericType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        throw new IllegalArgumentException("Cannot resolve List element type for " + rc.getName());
    }

    record JsonBindingPlan(Class<?> recordType,
                           List<FieldBinding> fields,
                           Map<String, FieldBinding> byJsonName,
                           Map<String, FieldBinding> byProtoName,
                           FieldBinding[] byComponentIndex,
                           MethodHandle constructor,
                           int componentCount) {}

    static final class FieldBinding {
        final int number;
        final FieldType type;
        final boolean repeated;
        final Class<?> elementType;
        final int componentIndex;
        final MethodHandle getter;
        final String protoName;
        final String jsonName;

        FieldBinding(int number, FieldType type, boolean repeated, Class<?> elementType,
                     int componentIndex, MethodHandle getter, String protoName, String jsonName) {
            this.number = number;
            this.type = type;
            this.repeated = repeated;
            this.elementType = elementType;
            this.componentIndex = componentIndex;
            this.getter = getter;
            this.protoName = protoName;
            this.jsonName = jsonName;
        }
    }
}
