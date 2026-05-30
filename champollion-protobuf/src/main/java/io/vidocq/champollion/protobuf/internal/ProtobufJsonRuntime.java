package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Duration;
import io.vidocq.champollion.protobuf.wkt.Empty;
import io.vidocq.champollion.protobuf.wkt.FieldMask;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.TypeRegistry;
import io.vidocq.champollion.protobuf.wkt.Wrappers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
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
 * Implementation of the Proto3 JSON Canonical Mapping. Wired to Jakarta JSON-P
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
        // Well-Known Types: specific canonical encoding (non-JSON object).
        if (writeWktTopLevel(message, gen)) return;
        gen.writeStartObject();

        // M6.9: retrieve the UnknownFieldSet to re-emit unknown enum values
        // (proto3 JSON §enum spec: preservation + re-emission as numeric).
        io.vidocq.champollion.protobuf.UnknownFieldSet unknownFields =
                readUnknownFieldSet(plan, message);

        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }

            // M6.9: collect unknown enum values for this field number.
            java.util.List<Long> unknownEnumVarints =
                    fb.type == FieldType.ENUM ? unknownVarintsFor(unknownFields, fb.number) : null;

            // Full skip: no known value AND no unknown enum to emit.
            if (value == null && unknownEnumVarints == null) continue;

            if (fb.type == FieldType.MAP) {
                Map<?, ?> map = (Map<?, ?>) value;
                if (map.isEmpty()) continue; // proto3 implicit presence
                gen.writeStartObject(fb.jsonName);
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = mapKeyAsString(fb.mapKeyType, e.getKey());
                    writeMapValue(fb.mapValueType, fb.mapValueClass, e.getValue(), gen, k);
                }
                gen.writeEnd();
                continue;
            }
            if (fb.repeated) {
                List<?> list = (List<?>) value;
                boolean emptyKnown = list == null || list.isEmpty();
                if (emptyKnown && unknownEnumVarints == null) continue; // proto3 canonical : omet [] vide
                gen.writeStartArray(fb.jsonName);
                if (list != null) {
                    for (Object item : list) {
                        if (item == null) continue; // unknown enum forward-compat
                        writeScalar(fb, item, gen, null);
                    }
                }
                if (unknownEnumVarints != null) {
                    // M6.9: append the numeric unknown enum values to the array.
                    for (Long v : unknownEnumVarints) gen.write(v.intValue());
                }
                gen.writeEnd();
            } else if (fb.type == FieldType.ENUM && unknownEnumVarints != null) {
                // M6.9: unknown enum value — the value was indeed set on the wire/JSON side,
                // just not mappable to a Java constant of the local enum. It takes precedence
                // over the proto3 default and must be re-emitted numerically (last-wins if
                // several wire occurrences).
                gen.write(fb.jsonName,
                        unknownEnumVarints.get(unknownEnumVarints.size() - 1).intValue());
            } else if (!fb.explicitPresence && isDefault(fb, value)) {
                continue; // proto3 implicit presence
            } else {
                writeScalar(fb, value, gen, fb.jsonName);
            }
        }
        gen.writeEnd();
    }

    /** M6.9: gets the record {@code UnknownFieldSet} if the component exists. */
    private static io.vidocq.champollion.protobuf.UnknownFieldSet readUnknownFieldSet(
            JsonBindingPlan plan, Object message) {
        if (plan.unknownFieldsIndex < 0 || plan.unknownFieldsGetter == null) return null;
        try {
            return (io.vidocq.champollion.protobuf.UnknownFieldSet)
                    plan.unknownFieldsGetter.invoke(message);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** M6.9: varints (unknown enum values) for a given field number, or {@code null}. */
    private static java.util.List<Long> unknownVarintsFor(
            io.vidocq.champollion.protobuf.UnknownFieldSet ufs, int fieldNumber) {
        if (ufs == null || ufs.isEmpty()) return null;
        io.vidocq.champollion.protobuf.UnknownFieldSet.Field f = ufs.get(fieldNumber);
        if (f == null || f.varints().isEmpty()) return null;
        return f.varints();
    }

    /** Convertit une key de map en string JSON (spec proto3 JSON §maps). */
    private static String mapKeyAsString(FieldType type, Object key) {
        if (key == null) {
            return switch (type) {
                case BOOL -> "false";
                case STRING -> "";
                default -> "0";
            };
        }
        return switch (type) {
            case BOOL -> Boolean.toString((boolean) key);
            case STRING -> (String) key;
            case INT32, SINT32, SFIXED32 -> Integer.toString((int) key);
            case UINT32, FIXED32 -> Long.toString(Integer.toUnsignedLong((int) key));
            case INT64, SINT64, SFIXED64 -> Long.toString((long) key);
            case UINT64, FIXED64 -> Long.toUnsignedString((long) key);
            default -> String.valueOf(key);
        };
    }

    /** Writes a map value according to its FieldType. Reuses the runtime writeScalar. */
    private static void writeMapValue(FieldType type, Class<?> messageClass, Object value,
                                      JsonGenerator gen, String key) {
        if (value == null) {
            gen.writeNull(key);
            return;
        }
        switch (type) {
            case BOOL -> gen.write(key, (boolean) value);
            case INT32, SINT32, SFIXED32 -> gen.write(key, (int) value);
            case UINT32, FIXED32 -> gen.write(key, Integer.toUnsignedLong((int) value));
            case INT64, SINT64, SFIXED64 -> gen.write(key, Long.toString((long) value));
            case UINT64, FIXED64 -> gen.write(key, Long.toUnsignedString((long) value));
            case FLOAT -> gen.write(key, (float) value);
            case DOUBLE -> gen.write(key, (double) value);
            case STRING -> gen.write(key, (String) value);
            case BYTES -> gen.write(key, java.util.Base64.getEncoder().encodeToString((byte[]) value));
            case ENUM -> gen.write(key, ((Enum<?>) value).name());
            case MESSAGE -> {
                if (writeWktAsValue(value, gen, key)) return;
                gen.writeStartObject(key);
                writeMessageBody(planFor(value.getClass()), value, gen);
                gen.writeEnd();
            }
            case MAP -> throw new IllegalStateException("Nested MAP is forbidden");
        }
    }

    private static void writeScalar(FieldBinding fb, Object value, JsonGenerator gen, String key) {
        switch (fb.type) {
            case INT32, SINT32, SFIXED32 -> {
                int v = (int) value;
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case UINT32, FIXED32 -> {
                // Proto3 JSON canonical: uint32 must always appear as
                // a non-negative integer — a "negative" Java int (= value > 2^31)
                // must be promoted to an unsigned long.
                long v = Integer.toUnsignedLong((int) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case INT64, SINT64, SFIXED64 -> {
                // Proto3 canonical: signed int64 always as a string.
                String v = Long.toString((long) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case UINT64, FIXED64 -> {
                // Proto3 canonical: unsigned uint64 always as a string,
                // in unsigned representation.
                String v = Long.toUnsignedString((long) value);
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
                // WKT as a nested field: emit the canonical flat value (string/number).
                if (writeWktAsValue(value, gen, key)) return;
                if (key != null) gen.writeStartObject(key); else gen.writeStartObject();
                JsonBindingPlan nested = planFor(value.getClass());
                writeMessageBody(nested, value, gen);
                gen.writeEnd();
            }
        }
    }

    // ============================================================ Well-Known Types

    /**
     * Emits the {@code message} as the top level of a JSON document if it
     * is a WKT. Returns {@code true} if handled.
     */
    private static boolean writeWktTopLevel(Object message, JsonGenerator gen) {
        if (message instanceof Empty) {
            gen.writeStartObject().writeEnd();
            return true;
        }
        if (message instanceof Timestamp t) {
            gen.write(formatTimestamp(t));
            return true;
        }
        if (message instanceof Duration d) {
            gen.write(formatDuration(d));
            return true;
        }
        if (message instanceof FieldMask m) {
            gen.write(formatFieldMask(m));
            return true;
        }
        if (message instanceof Any a) {
            writeAny(a, gen, null);
            return true;
        }
        if (message instanceof io.vidocq.champollion.protobuf.wkt.Struct s) {
            writeStructAsObject(s, gen, null);
            return true;
        }
        if (message instanceof io.vidocq.champollion.protobuf.wkt.ListValue lv) {
            writeListValueAsArray(lv, gen, null);
            return true;
        }
        if (message instanceof io.vidocq.champollion.protobuf.wkt.Value v) {
            writeValueDynamic(v, gen, null);
            return true;
        }
        if (message instanceof io.vidocq.champollion.protobuf.wkt.NullValue) {
            gen.writeNull();
            return true;
        }
        return writeWrapperTopLevel(message, gen);
    }

    private static void writeStructAsObject(io.vidocq.champollion.protobuf.wkt.Struct s,
                                            JsonGenerator gen, String key) {
        if (key != null) gen.writeStartObject(key); else gen.writeStartObject();
        if (s.fields() != null) {
            for (var entry : s.fields().entrySet()) {
                writeValueDynamic(entry.getValue(), gen, entry.getKey());
            }
        }
        gen.writeEnd();
    }

    private static void writeListValueAsArray(io.vidocq.champollion.protobuf.wkt.ListValue lv,
                                              JsonGenerator gen, String key) {
        if (key != null) gen.writeStartArray(key); else gen.writeStartArray();
        if (lv.values() != null) {
            for (var v : lv.values()) writeValueDynamic(v, gen, null);
        }
        gen.writeEnd();
    }

    /** Emits a Value while respecting its oneof — null if all sub-fields are null. */
    private static void writeValueDynamic(io.vidocq.champollion.protobuf.wkt.Value v,
                                          JsonGenerator gen, String key) {
        if (v == null) {
            if (key != null) gen.writeNull(key); else gen.writeNull();
            return;
        }
        if (v.nullValue() != null) {
            if (key != null) gen.writeNull(key); else gen.writeNull();
        } else if (v.numberValue() != null) {
            double d = v.numberValue();
            if (key != null) gen.write(key, d); else gen.write(d);
        } else if (v.stringValue() != null) {
            if (key != null) gen.write(key, v.stringValue()); else gen.write(v.stringValue());
        } else if (v.boolValue() != null) {
            if (key != null) gen.write(key, v.boolValue()); else gen.write(v.boolValue());
        } else if (v.structValue() != null) {
            writeStructAsObject(v.structValue(), gen, key);
        } else if (v.listValue() != null) {
            writeListValueAsArray(v.listValue(), gen, key);
        } else {
            // Absent de tous les sub-fields → null JSON (sentinelle).
            if (key != null) gen.writeNull(key); else gen.writeNull();
        }
    }

    private static boolean writeWktAsValue(Object v, JsonGenerator gen, String key) {
        if (v instanceof Empty) {
            if (key != null) gen.writeStartObject(key).writeEnd();
            else gen.writeStartObject().writeEnd();
            return true;
        }
        if (v instanceof Timestamp t) {
            String s = formatTimestamp(t);
            if (key != null) gen.write(key, s); else gen.write(s);
            return true;
        }
        if (v instanceof Duration d) {
            String s = formatDuration(d);
            if (key != null) gen.write(key, s); else gen.write(s);
            return true;
        }
        if (v instanceof FieldMask m) {
            String s = formatFieldMask(m);
            if (key != null) gen.write(key, s); else gen.write(s);
            return true;
        }
        if (v instanceof Any a) {
            writeAny(a, gen, key);
            return true;
        }
        if (v instanceof io.vidocq.champollion.protobuf.wkt.Struct s) {
            writeStructAsObject(s, gen, key);
            return true;
        }
        if (v instanceof io.vidocq.champollion.protobuf.wkt.ListValue lv) {
            writeListValueAsArray(lv, gen, key);
            return true;
        }
        if (v instanceof io.vidocq.champollion.protobuf.wkt.Value vv) {
            writeValueDynamic(vv, gen, key);
            return true;
        }
        return writeWrapperAsValue(v, gen, key);
    }

    // ============================================================ Any JSON canonical

    /**
     * Serializes an {@link Any} according to the canonical mapping:
     * <ul>
     *   <li>If the wrapped type is a WKT (Timestamp, Duration, etc.) →
     *       {@code {"@type":"...", "value":<wkt-form>}}</li>
     *   <li>Sinon → {@code {"@type":"...", ...champs aplatis}}</li>
     *   <li>Unknown type → degraded fallback
     *       {@code {"@type":"...", "value":"<base64>"}} (non strictement
     *       canonical but useful for opaque traversal)</li>
     * </ul>
     */
    private static void writeAny(Any any, JsonGenerator gen, String key) {
        String typeUrl = any.type_url();
        // Spec proto3 §any : Any default (typeUrl="" et value vide) → JSON object vide.
        if (typeUrl.isEmpty() && (any.value() == null || any.value().length == 0)) {
            if (key != null) gen.writeStartObject(key).writeEnd();
            else gen.writeStartObject().writeEnd();
            return;
        }
        String fullName = TypeRegistry.fullNameFromTypeUrl(typeUrl);
        Class<?> type = TypeRegistry.lookup(fullName);

        if (key != null) gen.writeStartObject(key);
        else gen.writeStartObject();
        gen.write("@type", typeUrl);

        if (type == null) {
            // Fallback: unknown type — emits the base64 value.
            gen.write("value", java.util.Base64.getEncoder().encodeToString(any.value()));
            gen.writeEnd();
            return;
        }

        // Decode the value into the concrete type.
        Object wrapped;
        try {
            wrapped = Protobuf.parser(type).parseFrom(any.value());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Cannot decode Any.value as " + type, e);
        }

        if (isWktClass(type) && type != Empty.class) {
            // WKT: a single "value" field with the WKT canonical format.
            writeWktAsValue(wrapped, gen, "value");
        } else if (type == Empty.class) {
            // Proto3 §any spec: Any wrapping Empty does NOT emit a "value" — only @type.
        } else {
            // Ordinary message: flatten fields at the same level as @type.
            JsonBindingPlan plan = planFor(type);
            writeMessageBody(plan, wrapped, gen);
        }
        gen.writeEnd();
    }

    /**
     * Lit un {@link Any} depuis un {@link JsonParser}. L'event {@code v}
     * passed as a parameter is the {@code START_OBJECT} that begins the Any object.
     * We buffer the whole object in a {@link JsonObject} DOM so we can read
     * {@code @type} before the rest — JSON is unordered.
     */
    private static Any readAny(JsonParser parser) throws java.io.IOException {
        return readAnyFromObject(parser.getObject());
    }

    private static Any readAnyFromObject(JsonObject obj) throws java.io.IOException {
        // Spec: empty Any {} = default Any (type_url="", value=byte[0]).
        if (obj.isEmpty()) return new Any("", new byte[0]);
        if (!obj.containsKey("@type")) {
            throw new java.io.IOException("Any object must contain @type");
        }
        String typeUrl = obj.getString("@type");
        // Proto3 §any spec: @type must be of the form '<base>/<full.name>'
        // (typically type.googleapis.com/<full.name>). No slash = invalid.
        // Also: empty @type with other fields present = invalid (a partial Any
        // is ambiguous).
        if (typeUrl.indexOf('/') < 0) {
            if (typeUrl.isEmpty() && obj.size() == 1) {
                // {"@type": ""} seul = Any default.
                return new Any("", new byte[0]);
            }
            throw new java.io.IOException("Any.@type invalid: '" + typeUrl + "' (expected base/full.name)");
        }
        String fullName = TypeRegistry.fullNameFromTypeUrl(typeUrl);
        Class<?> type = TypeRegistry.lookup(fullName);

        byte[] valueBytes;
        if (type == null) {
            // Fallback : value comme base64
            JsonValue v = obj.get("value");
            if (v == null) valueBytes = new byte[0];
            else if (v instanceof JsonString js) {
                valueBytes = java.util.Base64.getDecoder().decode(js.getString());
            } else {
                valueBytes = new byte[0];
            }
            return new Any(typeUrl, valueBytes);
        }

        if (isWktClass(type)) {
            // WKT: a single "value" field to parse through the WKT machinery
            JsonValue v = obj.get("value");
            if (v == null) {
                if (type == Empty.class) {
                    valueBytes = new byte[0];
                } else {
                    throw new java.io.IOException("Any/" + fullName + " missing required 'value'");
                }
            } else if (type == Any.class && v instanceof JsonObject jo) {
                // Special case: Any wrapping Any. Recurse in DOM to avoid
                // re-tokenizing via fromJsonString (which can loop in streaming).
                Any nested = readAnyFromObject(jo);
                valueBytes = Protobuf.toByteArray(nested);
            } else {
                String wktJson = jsonValueToString(v);
                Object wrapped = fromJsonString(type, wktJson);
                valueBytes = Protobuf.toByteArray(wrapped);
            }
            return new Any(typeUrl, valueBytes);
        }

        // Message ordinaire : reconstruire un JSON object sans @type, parser via runtime
        var builder = Json.createObjectBuilder();
        for (var entry : obj.entrySet()) {
            if (!entry.getKey().equals("@type")) builder.add(entry.getKey(), entry.getValue());
        }
        JsonObject withoutType = builder.build();
        String inner = jsonValueToString(withoutType);
        Object wrapped = fromJsonString(type, inner);
        valueBytes = Protobuf.toByteArray(wrapped);
        return new Any(typeUrl, valueBytes);
    }

    private static String jsonValueToString(JsonValue v) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try (JsonWriter w = Json.createWriter(sw)) {
            w.write(v);
        }
        return sw.toString();
    }

    private static boolean writeWrapperTopLevel(Object v, JsonGenerator gen) {
        return switch (v) {
            case Wrappers.DoubleValue w -> { writeWrapperDouble(w.value(), gen, null); yield true; }
            case Wrappers.FloatValue w -> { writeWrapperFloat(w.value(), gen, null); yield true; }
            case Wrappers.Int64Value w -> { gen.write(Long.toString(w.value())); yield true; }
            case Wrappers.UInt64Value w -> { gen.write(Long.toString(w.value())); yield true; }
            case Wrappers.Int32Value w -> { gen.write(w.value()); yield true; }
            case Wrappers.UInt32Value w -> { gen.write(w.value()); yield true; }
            case Wrappers.BoolValue w -> { gen.write(w.value()); yield true; }
            case Wrappers.StringValue w -> { gen.write(w.value()); yield true; }
            case Wrappers.BytesValue w -> { gen.write(java.util.Base64.getEncoder().encodeToString(w.value())); yield true; }
            default -> false;
        };
    }

    private static boolean writeWrapperAsValue(Object v, JsonGenerator gen, String key) {
        return switch (v) {
            case Wrappers.DoubleValue w -> { writeWrapperDouble(w.value(), gen, key); yield true; }
            case Wrappers.FloatValue w -> { writeWrapperFloat(w.value(), gen, key); yield true; }
            case Wrappers.Int64Value w -> {
                String s = Long.toString(w.value());
                if (key != null) gen.write(key, s); else gen.write(s);
                yield true;
            }
            case Wrappers.UInt64Value w -> {
                String s = Long.toString(w.value());
                if (key != null) gen.write(key, s); else gen.write(s);
                yield true;
            }
            case Wrappers.Int32Value w -> {
                if (key != null) gen.write(key, w.value()); else gen.write(w.value());
                yield true;
            }
            case Wrappers.UInt32Value w -> {
                if (key != null) gen.write(key, w.value()); else gen.write(w.value());
                yield true;
            }
            case Wrappers.BoolValue w -> {
                if (key != null) gen.write(key, w.value()); else gen.write(w.value());
                yield true;
            }
            case Wrappers.StringValue w -> {
                if (key != null) gen.write(key, w.value()); else gen.write(w.value());
                yield true;
            }
            case Wrappers.BytesValue w -> {
                String s = java.util.Base64.getEncoder().encodeToString(w.value());
                if (key != null) gen.write(key, s); else gen.write(s);
                yield true;
            }
            default -> false;
        };
    }

    private static void writeWrapperDouble(double v, JsonGenerator gen, String key) {
        if (Double.isNaN(v)) { if (key != null) gen.write(key, "NaN"); else gen.write("NaN"); }
        else if (v == Double.POSITIVE_INFINITY) { if (key != null) gen.write(key, "Infinity"); else gen.write("Infinity"); }
        else if (v == Double.NEGATIVE_INFINITY) { if (key != null) gen.write(key, "-Infinity"); else gen.write("-Infinity"); }
        else { if (key != null) gen.write(key, v); else gen.write(v); }
    }

    private static void writeWrapperFloat(float v, JsonGenerator gen, String key) {
        if (Float.isNaN(v)) { if (key != null) gen.write(key, "NaN"); else gen.write("NaN"); }
        else if (v == Float.POSITIVE_INFINITY) { if (key != null) gen.write(key, "Infinity"); else gen.write("Infinity"); }
        else if (v == Float.NEGATIVE_INFINITY) { if (key != null) gen.write(key, "-Infinity"); else gen.write("-Infinity"); }
        else { if (key != null) gen.write(key, v); else gen.write(v); }
    }

    /**
     * Spec : {@code google.protobuf.Timestamp.proto} — Restricted to dates
     * between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59.999999999Z.
     */
    private static final long TIMESTAMP_SECONDS_MIN = -62135596800L;
    private static final long TIMESTAMP_SECONDS_MAX = 253402300799L;
    /**
     * Spec : {@code google.protobuf.Duration.proto} — Range approximately
     * ±10,000 years.
     */
    private static final long DURATION_SECONDS_MIN = -315_576_000_000L;
    private static final long DURATION_SECONDS_MAX = 315_576_000_000L;
    private static final int NANOS_MAX = 999_999_999;

    private static String formatTimestamp(Timestamp t) {
        long sec = t.seconds();
        int nanos = t.nanos();
        if (sec < TIMESTAMP_SECONDS_MIN || sec > TIMESTAMP_SECONDS_MAX) {
            throw new IllegalArgumentException(
                    "Timestamp seconds out of range [" + TIMESTAMP_SECONDS_MIN
                            + ", " + TIMESTAMP_SECONDS_MAX + "]: " + sec);
        }
        if (nanos < 0 || nanos > NANOS_MAX) {
            throw new IllegalArgumentException(
                    "Timestamp nanos out of range [0, " + NANOS_MAX + "]: " + nanos);
        }
        Instant instant = t.toInstant();
        // RFC 3339 / ISO-8601 — fraction nanoseconde optionnelle, toujours suffixe Z.
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static String formatDuration(Duration d) {
        long sec = d.seconds();
        int nanos = d.nanos();
        if (sec < DURATION_SECONDS_MIN || sec > DURATION_SECONDS_MAX) {
            throw new IllegalArgumentException(
                    "Duration seconds out of range [" + DURATION_SECONDS_MIN
                            + ", " + DURATION_SECONDS_MAX + "]: " + sec);
        }
        if (nanos < -NANOS_MAX || nanos > NANOS_MAX) {
            throw new IllegalArgumentException(
                    "Duration nanos out of range [-" + NANOS_MAX + ", "
                            + NANOS_MAX + "]: " + nanos);
        }
        // Le signe de nanos doit matcher celui de seconds (sauf si l'un est 0).
        if (sec != 0 && nanos != 0 && (sec > 0) != (nanos > 0)) {
            throw new IllegalArgumentException(
                    "Duration seconds and nanos must have the same sign: "
                            + sec + "s " + nanos + "ns");
        }
        StringBuilder sb = new StringBuilder();
        // Negative representation: sign on seconds OR nanos (proto guarantees the same sign).
        boolean negative = sec < 0 || nanos < 0;
        if (negative) {
            sb.append('-');
            sec = -sec;
            nanos = -nanos;
        }
        sb.append(sec);
        if (nanos > 0) {
            sb.append('.');
            // Adaptive precision: 3, 6 or 9 digits depending on what is needed.
            String s = String.format("%09d", nanos);
            int trim = s.length();
            while (trim > 3 && s.charAt(trim - 1) == '0') trim--;
            // Rounded to a multiple of 3 (3, 6, or 9 canonical digits).
            int width = trim <= 3 ? 3 : (trim <= 6 ? 6 : 9);
            sb.append(s, 0, width);
        }
        sb.append('s');
        return sb.toString();
    }

    private static String formatFieldMask(FieldMask m) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String path : m.paths()) {
            if (!first) sb.append(',');
            first = false;
            // Conversion snake_case → camelCase on each path segment.
            String[] segments = path.split("\\.");
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(Descriptors.toJsonName(segments[i]));
            }
        }
        return sb.toString();
    }

    private static Timestamp parseTimestamp(String s) {
        // Strict RFC 3339: uppercase T, Z suffix (or numeric offset).
        // ISO_INSTANT de Java accepte lowercase via parseFlexible — on rejette explicitement.
        if (s.indexOf('t') >= 0) {
            throw new IllegalArgumentException("Timestamp T must be uppercase: " + s);
        }
        // Z suffix accepted in uppercase only (RFC 3339 §5.6 says 'Z' or '±hh:mm').
        // No lowercase 'z'.
        if (s.indexOf('z') >= 0) {
            throw new IllegalArgumentException("Timestamp Z must be uppercase: " + s);
        }
        // The character 'T' must be present somewhere (RFC 3339 §5.6 date-time).
        if (s.indexOf('T') < 0) {
            throw new IllegalArgumentException("Timestamp missing T separator: " + s);
        }
        Instant instant = DateTimeFormatter.ISO_INSTANT.parse(s, Instant::from);
        Timestamp t = Timestamp.from(instant);
        // Validates the bounds (same as serialization).
        if (t.seconds() < TIMESTAMP_SECONDS_MIN || t.seconds() > TIMESTAMP_SECONDS_MAX) {
            throw new IllegalArgumentException(
                    "Timestamp out of range: " + s);
        }
        return t;
    }

    private static Duration parseDuration(String s) {
        if (!s.endsWith("s")) {
            throw new IllegalArgumentException("Duration must end with 's': " + s);
        }
        String body = s.substring(0, s.length() - 1);
        boolean negative = body.startsWith("-");
        if (negative) body = body.substring(1);
        int dot = body.indexOf('.');
        long sec;
        int nanos = 0;
        if (dot < 0) {
            sec = Long.parseLong(body);
        } else {
            sec = Long.parseLong(body.substring(0, dot));
            String frac = body.substring(dot + 1);
            // Pad to 9 digits or truncate if longer (the first 9).
            if (frac.length() > 9) frac = frac.substring(0, 9);
            else while (frac.length() < 9) frac += "0";
            nanos = Integer.parseInt(frac);
        }
        if (negative) { sec = -sec; nanos = -nanos; }
        // Valide les bornes ±315_576_000_000.
        if (sec < DURATION_SECONDS_MIN || sec > DURATION_SECONDS_MAX) {
            throw new IllegalArgumentException("Duration out of range: " + s);
        }
        return new Duration(sec, nanos);
    }

    private static FieldMask parseFieldMask(String s) {
        if (s.isEmpty()) return new FieldMask(List.of());
        // Reverse conversion camelCase → snake_case on each path segment.
        String[] paths = s.split(",");
        List<String> out = new ArrayList<>(paths.length);
        for (String p : paths) {
            StringBuilder buf = new StringBuilder();
            String[] segments = p.split("\\.");
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) buf.append('.');
                for (int j = 0; j < segments[i].length(); j++) {
                    char c = segments[i].charAt(j);
                    if (Character.isUpperCase(c)) {
                        buf.append('_').append(Character.toLowerCase(c));
                    } else {
                        buf.append(c);
                    }
                }
            }
            out.add(buf.toString());
        }
        return new FieldMask(out);
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
                for (Object item : list) {
                    if (item == null) continue;
                    writeScalar(fb, item, gen, null);
                }
                gen.writeEnd();
            } else if (isDefault(fb, value)) {
                continue;
            } else {
                writeScalar(fb, value, gen, fb.jsonName);
            }
        }
        // Emits the unknown enum values stored in UnknownFieldSet (proto3 JSON §enum spec:
        // forward-compat numeric preservation).
        if (plan.unknownFieldsIndex >= 0) {
            try {
                io.vidocq.champollion.protobuf.UnknownFieldSet ufs =
                        (io.vidocq.champollion.protobuf.UnknownFieldSet)
                                plan.unknownFieldsGetter.invoke(message);
                if (ufs != null && !ufs.isEmpty()) {
                    for (var entry : ufs.asMap().entrySet()) {
                        int fn = entry.getKey();
                        FieldBinding fb = null;
                        for (FieldBinding f : plan.fields) {
                            if (f.number == fn) { fb = f; break; }
                        }
                        if (fb == null || fb.type != FieldType.ENUM) continue;
                        var varints = entry.getValue().varints();
                        if (varints.isEmpty()) continue;
                        if (fb.repeated) {
                            gen.writeStartArray(fb.jsonName);
                            for (long v : varints) gen.write((int) v);
                            gen.writeEnd();
                        } else {
                            // last-wins pour singular ENUM
                            gen.write(fb.jsonName, (int) (long) varints.get(varints.size() - 1));
                        }
                    }
                }
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    // ============================================================ Read

    @SuppressWarnings("unchecked")
    public static <T> T fromJsonString(Class<T> type, String json) throws IOException {
        try (JsonParser parser = Json.createParser(new StringReader(json))) {
            if (!parser.hasNext()) {
                throw new IOException("Empty JSON for " + type.getSimpleName());
            }
            JsonParser.Event first = parser.next();
            // WKT: the top-level representation is not an object (except Empty).
            if (isWktClass(type) && type != Empty.class) {
                return (T) readWktValue(type, parser, first);
            }
            if (first != JsonParser.Event.START_OBJECT) {
                throw new IOException("Expected JSON object for " + type.getSimpleName() + ", got " + first);
            }
            if (type == Empty.class) {
                // Consume the END_OBJECT and return the singleton.
                while (parser.hasNext() && parser.next() != JsonParser.Event.END_OBJECT) {
                    // skip unexpected fields
                }
                return (T) Empty.INSTANCE;
            }
            JsonBindingPlan plan = planFor(type);
            return (T) readMessage(plan, parser);
        }
    }

    private static boolean isWktClass(Class<?> type) {
        return type == Timestamp.class
                || type == Duration.class
                || type == Empty.class
                || type == FieldMask.class
                || type == Any.class
                || type == io.vidocq.champollion.protobuf.wkt.Struct.class
                || type == io.vidocq.champollion.protobuf.wkt.Value.class
                || type == io.vidocq.champollion.protobuf.wkt.ListValue.class
                || type == io.vidocq.champollion.protobuf.wkt.NullValue.class
                || type == Wrappers.DoubleValue.class
                || type == Wrappers.FloatValue.class
                || type == Wrappers.Int64Value.class
                || type == Wrappers.UInt64Value.class
                || type == Wrappers.Int32Value.class
                || type == Wrappers.UInt32Value.class
                || type == Wrappers.BoolValue.class
                || type == Wrappers.StringValue.class
                || type == Wrappers.BytesValue.class;
    }

    private static io.vidocq.champollion.protobuf.wkt.Struct readStructFromObject(JsonParser parser) throws IOException {
        java.util.LinkedHashMap<String, io.vidocq.champollion.protobuf.wkt.Value> fields = new java.util.LinkedHashMap<>();
        while (parser.hasNext()) {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.END_OBJECT) break;
            if (e != JsonParser.Event.KEY_NAME) throw new IOException("Expected key, got " + e);
            String key = parser.getString();
            JsonParser.Event ev = parser.next();
            fields.put(key, readValueDynamic(parser, ev));
        }
        return new io.vidocq.champollion.protobuf.wkt.Struct(fields);
    }

    private static io.vidocq.champollion.protobuf.wkt.ListValue readListValueFromArray(JsonParser parser) throws IOException {
        java.util.ArrayList<io.vidocq.champollion.protobuf.wkt.Value> values = new java.util.ArrayList<>();
        while (parser.hasNext()) {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.END_ARRAY) break;
            values.add(readValueDynamic(parser, e));
        }
        return new io.vidocq.champollion.protobuf.wkt.ListValue(values);
    }

    /** Parses a JSON value into a Value (dispatch on the JSON event type). */
    private static io.vidocq.champollion.protobuf.wkt.Value readValueDynamic(JsonParser parser, JsonParser.Event v) throws IOException {
        return switch (v) {
            case VALUE_NULL -> io.vidocq.champollion.protobuf.wkt.Value.ofNull();
            case VALUE_TRUE -> io.vidocq.champollion.protobuf.wkt.Value.ofBool(true);
            case VALUE_FALSE -> io.vidocq.champollion.protobuf.wkt.Value.ofBool(false);
            case VALUE_STRING -> io.vidocq.champollion.protobuf.wkt.Value.ofString(parser.getString());
            case VALUE_NUMBER -> io.vidocq.champollion.protobuf.wkt.Value.ofNumber(parser.getBigDecimal().doubleValue());
            case START_OBJECT -> io.vidocq.champollion.protobuf.wkt.Value.ofStruct(readStructFromObject(parser));
            case START_ARRAY -> io.vidocq.champollion.protobuf.wkt.Value.ofList(readListValueFromArray(parser));
            default -> throw new IOException("Unexpected JSON event for Value: " + v);
        };
    }

    private static Object readWktValue(Class<?> type, JsonParser parser, JsonParser.Event v) throws IOException {
        if (type == Any.class) {
            if (v != JsonParser.Event.START_OBJECT) {
                throw new IOException("Expected JSON object for Any, got " + v);
            }
            return readAny(parser);
        }
        if (type == Timestamp.class) {
            requireString(v, "Timestamp");
            return parseTimestamp(parser.getString());
        }
        if (type == Duration.class) {
            requireString(v, "Duration");
            return parseDuration(parser.getString());
        }
        if (type == FieldMask.class) {
            requireString(v, "FieldMask");
            return parseFieldMask(parser.getString());
        }
        if (type == io.vidocq.champollion.protobuf.wkt.Struct.class) {
            if (v != JsonParser.Event.START_OBJECT) {
                throw new IOException("Expected JSON object for Struct, got " + v);
            }
            return readStructFromObject(parser);
        }
        if (type == io.vidocq.champollion.protobuf.wkt.ListValue.class) {
            if (v != JsonParser.Event.START_ARRAY) {
                throw new IOException("Expected JSON array for ListValue, got " + v);
            }
            return readListValueFromArray(parser);
        }
        if (type == io.vidocq.champollion.protobuf.wkt.Value.class) {
            return readValueDynamic(parser, v);
        }
        if (type == io.vidocq.champollion.protobuf.wkt.NullValue.class) {
            // Accept any JSON value (the spec says NullValue maps to JSON null, but
            // the other values are also mapped to NULL_VALUE in case of invariant).
            if (v != JsonParser.Event.VALUE_NULL) {
                throw new IOException("Expected JSON null for NullValue, got " + v);
            }
            return io.vidocq.champollion.protobuf.wkt.NullValue.NULL_VALUE;
        }
        if (type == Wrappers.DoubleValue.class) {
            return new Wrappers.DoubleValue(readFloating(parser, v).doubleValue());
        }
        if (type == Wrappers.FloatValue.class) {
            return new Wrappers.FloatValue(readFloating(parser, v).floatValue());
        }
        if (type == Wrappers.Int64Value.class) {
            return new Wrappers.Int64Value(readInt64(parser, v));
        }
        if (type == Wrappers.UInt64Value.class) {
            return new Wrappers.UInt64Value(readInt64(parser, v));
        }
        if (type == Wrappers.Int32Value.class) {
            return new Wrappers.Int32Value(readInt32(parser, v));
        }
        if (type == Wrappers.UInt32Value.class) {
            return new Wrappers.UInt32Value(readInt32(parser, v));
        }
        if (type == Wrappers.BoolValue.class) {
            if (v == JsonParser.Event.VALUE_TRUE) return new Wrappers.BoolValue(true);
            if (v == JsonParser.Event.VALUE_FALSE) return new Wrappers.BoolValue(false);
            throw new IOException("Expected boolean for BoolValue, got " + v);
        }
        if (type == Wrappers.StringValue.class) {
            requireString(v, "StringValue");
            return new Wrappers.StringValue(parser.getString());
        }
        if (type == Wrappers.BytesValue.class) {
            requireString(v, "BytesValue");
            return new Wrappers.BytesValue(java.util.Base64.getDecoder().decode(parser.getString()));
        }
        throw new IOException("Not a WKT class: " + type);
    }

    private static void requireString(JsonParser.Event v, String wkt) throws IOException {
        if (v != JsonParser.Event.VALUE_STRING) {
            throw new IOException("Expected JSON string for " + wkt + ", got " + v);
        }
    }

    private static long readInt64(JsonParser parser, JsonParser.Event v) throws IOException {
        if (v == JsonParser.Event.VALUE_STRING) return Long.parseLong(parser.getString());
        if (v == JsonParser.Event.VALUE_NUMBER) return parser.getLong();
        throw new IOException("Expected int64 value, got " + v);
    }

    private static int readInt32(JsonParser parser, JsonParser.Event v) throws IOException {
        if (v == JsonParser.Event.VALUE_NUMBER) return parser.getInt();
        if (v == JsonParser.Event.VALUE_STRING) return Integer.parseInt(parser.getString());
        throw new IOException("Expected int32 value, got " + v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readMessage(JsonBindingPlan plan, JsonParser parser) throws IOException {
        Object[] slots = new Object[plan.componentCount];
        boolean[] hasValue = new boolean[plan.componentCount];
        java.util.HashMap<String, String> seenOneofs = new java.util.HashMap<>();
        // Side-channel pour preserver les unknown enum values (proto3 JSON §enum :
        // unmapped integer values are preserved and re-emitted numerically).
        io.vidocq.champollion.protobuf.UnknownFieldSet.Builder unknownFieldsBuilder =
                plan.unknownFieldsIndex >= 0
                        ? io.vidocq.champollion.protobuf.UnknownFieldSet.newBuilder()
                        : null;

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
                // Special WKT Value case: JSON null → Value(null_value=NULL_VALUE)
                // (spec §google.protobuf.Value). For other types, null = absent.
                if (fb.elementType == io.vidocq.champollion.protobuf.wkt.Value.class) {
                    slots[fb.componentIndex] = io.vidocq.champollion.protobuf.wkt.Value.ofNull();
                    hasValue[fb.componentIndex] = true;
                }
                continue;
            }
            // Reject two property keys from the same oneofGroup
            if (fb.oneofGroup != null && !fb.oneofGroup.isEmpty()) {
                String already = seenOneofs.put(fb.oneofGroup, key);
                if (already != null) {
                    throw new IOException("oneof '" + fb.oneofGroup
                            + "': '" + key + "' duplicate, '" + already + "' already set");
                }
            }
            if (fb.type == FieldType.MAP) {
                if (v != JsonParser.Event.START_OBJECT) {
                    throw new IOException("Expected object for map field " + key);
                }
                java.util.LinkedHashMap<Object, Object> map = new java.util.LinkedHashMap<>();
                slots[fb.componentIndex] = map;
                hasValue[fb.componentIndex] = true;
                while (parser.hasNext()) {
                    JsonParser.Event ek = parser.next();
                    if (ek == JsonParser.Event.END_OBJECT) break;
                    if (ek != JsonParser.Event.KEY_NAME) {
                        throw new IOException("Expected map key, got " + ek);
                    }
                    String rawKey = parser.getString();
                    Object mk = parseMapKey(fb.mapKeyType, rawKey);
                    JsonParser.Event ev = parser.next();
                    Object mv = readMapValue(fb.mapValueType, fb.mapValueClass, parser, ev);
                    map.put(mk, mv);
                }
                continue;
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
                    Object scalar = readScalar(fb, parser, next);
                    if (scalar instanceof UnknownEnumInt uei) {
                        if (unknownFieldsBuilder != null) {
                            unknownFieldsBuilder.recordVarint(fb.number, uei.value());
                        }
                        // No addition to the list — a Java enum cannot carry an
                        // unknown value. The wire/JSON side re-emits via UnknownFieldSet.
                    } else {
                        list.add(scalar);
                    }
                }
            } else {
                Object scalar = readScalar(fb, parser, v);
                if (scalar instanceof UnknownEnumInt uei) {
                    if (unknownFieldsBuilder != null) {
                        unknownFieldsBuilder.recordVarint(fb.number, uei.value());
                    }
                    // slots[idx] stays null — will be defaulted below.
                } else {
                    slots[fb.componentIndex] = scalar;
                    hasValue[fb.componentIndex] = true;
                }
            }
        }

        // UnknownFieldSet slot: inject the builder if present (preserve unknown enum + future unknowns).
        if (plan.unknownFieldsIndex >= 0) {
            slots[plan.unknownFieldsIndex] = unknownFieldsBuilder != null
                    ? unknownFieldsBuilder.build()
                    : io.vidocq.champollion.protobuf.UnknownFieldSet.EMPTY;
        }
        // Fill in proto3 defaults.
        for (int i = 0; i < slots.length; i++) {
            if (hasValue[i]) continue;
            if (i == plan.unknownFieldsIndex) continue;
            FieldBinding fb = plan.byComponentIndex[i];
            if (fb == null) {
                // Component without @ProtobufField (e.g. UnknownFieldSet). Default by type.
                Class<?> ct = plan.recordType.getRecordComponents()[i].getType();
                if (ct == io.vidocq.champollion.protobuf.UnknownFieldSet.class) {
                    slots[i] = io.vidocq.champollion.protobuf.UnknownFieldSet.EMPTY;
                } else {
                    slots[i] = null;
                }
                continue;
            }
            slots[i] = defaultFor(fb);
        }
        try {
            return plan.constructor.invokeWithArguments(slots);
        } catch (Throwable t) {
            throw new IOException("Cannot invoke constructor of " + plan.recordType, t);
        }
    }

    /** Sentinelle interne pour porter un enum value entier dont la mapping est inconnue. */
    private record UnknownEnumInt(int value) {}

    private static Object readScalar(FieldBinding fb, JsonParser parser, JsonParser.Event v) throws IOException {
        return switch (fb.type) {
            case INT32, SINT32, SFIXED32 -> readInt32Range(parser, v);
            case UINT32, FIXED32 -> {
                long raw = readUInt32Range(parser, v);
                yield (int) raw;
            }
            case INT64, SINT64, SFIXED64 -> readInt64Range(parser, v);
            case UINT64, FIXED64 -> {
                java.math.BigDecimal bd = readBigDecimal(parser, v, "uint64");
                try {
                    java.math.BigInteger bi = bd.toBigIntegerExact();
                    java.math.BigInteger max = new java.math.BigInteger("FFFFFFFFFFFFFFFF", 16);
                    if (bi.signum() < 0 || bi.compareTo(max) > 0) {
                        throw new IOException("uint64 out of range: " + bd);
                    }
                    yield bi.longValue(); // bit-preserving
                } catch (ArithmeticException e) {
                    throw new IOException("uint64 expects integer-valued JSON, got " + bd);
                }
            }
            case BOOL -> {
                if (v == JsonParser.Event.VALUE_TRUE) yield true;
                if (v == JsonParser.Event.VALUE_FALSE) yield false;
                throw new IOException("Expected boolean, got " + v);
            }
            case FLOAT -> {
                Number n = readFloating(parser, v);
                float f = n.floatValue();
                // A non-special value that overflows to infinity during the cast = out of range.
                if (Float.isInfinite(f) && !Double.isInfinite(n.doubleValue())) {
                    throw new IOException("float JSON value out of range: " + n);
                }
                yield f;
            }
            case DOUBLE -> {
                Number n = readFloating(parser, v);
                double d = n.doubleValue();
                // A BigDecimal outside double range → Double.NEGATIVE/POSITIVE_INFINITY.
                // We reject only if the input was not explicitly "Infinity"/"-Infinity".
                if (Double.isInfinite(d) && n instanceof java.math.BigDecimal) {
                    throw new IOException("double JSON value out of range: " + n);
                }
                yield d;
            }
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
                    // Proto3 JSON canonical: unknown enum value is tolerated (forward compat).
                    yield null;
                }
                if (v == JsonParser.Event.VALUE_NUMBER) {
                    // Proto3 JSON §enum spec: integer value accepted + unknown
                    // value preserved numerically during reserialization. If EnumValueMap
                    // does not map it → return UnknownEnumInt(value), which the caller
                    // stores in UnknownFieldSet for re-emission.
                    int protoValue = parser.getInt();
                    Object mapped = EnumValueMap.forClass(fb.elementType).byValue(protoValue);
                    if (mapped != null) yield mapped;
                    yield new UnknownEnumInt(protoValue);
                }
                if (v == JsonParser.Event.VALUE_NULL) yield null;
                throw new IOException("Expected enum value, got " + v);
            }
            case MESSAGE -> {
                // Nested WKT: the value is a scalar (string/number/bool) except Empty.
                if (isWktClass(fb.elementType) && fb.elementType != Empty.class) {
                    yield readWktValue(fb.elementType, parser, v);
                }
                if (v != JsonParser.Event.START_OBJECT) {
                    throw new IOException("Expected message object, got " + v);
                }
                if (fb.elementType == Empty.class) {
                    while (parser.hasNext() && parser.next() != JsonParser.Event.END_OBJECT) {}
                    yield Empty.INSTANCE;
                }
                yield readMessage(planFor(fb.elementType), parser);
            }
            case MAP -> throw new IOException("MAP type not yet implemented in JSON read scalar branch");
        };
    }

    /** Accepte {@code 1}, {@code 1.0}, {@code 1e5}, {@code "1"} etc.
     * Rejects real fractions and out-of-range INT32 values. */
    private static int readInt32Range(JsonParser parser, JsonParser.Event v) throws IOException {
        java.math.BigDecimal bd = readBigDecimal(parser, v, "int32");
        try {
            java.math.BigInteger bi = bd.toBigIntegerExact();
            if (bi.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || bi.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IOException("int32 out of range: " + bd);
            }
            return bi.intValue();
        } catch (ArithmeticException e) {
            throw new IOException("int32 expects integer-valued JSON, got " + bd);
        }
    }

    private static long readUInt32Range(JsonParser parser, JsonParser.Event v) throws IOException {
        java.math.BigDecimal bd = readBigDecimal(parser, v, "uint32");
        try {
            java.math.BigInteger bi = bd.toBigIntegerExact();
            if (bi.signum() < 0
                || bi.compareTo(java.math.BigInteger.valueOf(0xFFFFFFFFL)) > 0) {
                throw new IOException("uint32 out of range: " + bd);
            }
            return bi.longValue();
        } catch (ArithmeticException e) {
            throw new IOException("uint32 expects integer-valued JSON, got " + bd);
        }
    }

    private static long readInt64Range(JsonParser parser, JsonParser.Event v) throws IOException {
        java.math.BigDecimal bd = readBigDecimal(parser, v, "int64");
        try {
            java.math.BigInteger bi = bd.toBigIntegerExact();
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IOException("int64 out of range: " + bd);
            }
            return bi.longValue();
        } catch (ArithmeticException e) {
            throw new IOException("int64 expects integer-valued JSON, got " + bd);
        }
    }

    private static java.math.BigDecimal readBigDecimal(JsonParser parser, JsonParser.Event v,
                                                       String typeName) throws IOException {
        java.math.BigDecimal bd;
        if (v == JsonParser.Event.VALUE_NUMBER) {
            bd = parser.getBigDecimal();
        } else if (v == JsonParser.Event.VALUE_STRING) {
            try {
                bd = new java.math.BigDecimal(parser.getString());
            } catch (NumberFormatException e) {
                throw new IOException("Cannot parse " + typeName + " from string: " + parser.getString());
            }
        } else {
            throw new IOException("Expected " + typeName + "-compatible JSON, got " + v);
        }
        // Early reject: a BigDecimal with an integer part > 20 digits can never
        // represent a uint64 (max 18446744073709551615, 20 digits).
        // Avoids toBigIntegerExact() materializing a gigantic number
        // (e.g. "1e536870000") and timing out the JVM.
        int integerDigits = bd.precision() - bd.scale();
        if (integerDigits > 20) {
            throw new IOException(typeName + " value out of range (integerDigits=" + integerDigits + ")");
        }
        return bd;
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
        // scalar: already consumed by parser.next()
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
            case ENUM -> EnumValueMap.forClass(((Enum<?>) value).getClass()).valueOf((Enum<?>) value) == 0;
            case MESSAGE -> false;
            case MAP -> ((java.util.Map<?, ?>) value).isEmpty();
        };
    }

    private static Object defaultFor(FieldBinding fb) {
        if (fb.repeated) return List.of();
        // explicitPresence + non-primitive type: an absent field remains null
        // (oneof / proto2 EXPLICIT semantics). Consistent with RuntimeBinding.defaultFor.
        if (fb.explicitPresence && !fb.elementType.isPrimitive()) {
            return null;
        }
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
            case MAP -> new java.util.LinkedHashMap<>();
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
            lookup = MethodHandles.publicLookup();
        }
        List<FieldBinding> fields = new ArrayList<>();
        Map<String, FieldBinding> byJsonName = new HashMap<>();
        Map<String, FieldBinding> byProtoName = new HashMap<>();
        FieldBinding[] byComponentIndex = new FieldBinding[components.length];
        int unknownFieldsIndex = -1;
        MethodHandle unknownFieldsGetter = null;

        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            componentTypes[i] = rc.getType();
            if (rc.getType() == io.vidocq.champollion.protobuf.UnknownFieldSet.class
                    && "unknownFields".equals(rc.getName())) {
                unknownFieldsIndex = i;
                try {
                    unknownFieldsGetter = lookup.unreflect(rc.getAccessor());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                continue;
            }
            ProtobufField pf = rc.getAnnotation(ProtobufField.class);
            if (pf == null) continue;
            boolean isMap = pf.type() == FieldType.MAP;
            boolean repeated = !isMap && List.class.isAssignableFrom(rc.getType());
            Class<?> element;
            FieldType mapKeyType = null;
            FieldType mapValueType = null;
            Class<?> mapValueClass = null;
            if (isMap) {
                mapKeyType = pf.mapKey();
                mapValueType = pf.mapValue();
                mapValueClass = resolveMapValue(rc);
                element = java.util.Map.class;
            } else if (repeated) {
                element = resolveListElement(rc);
            } else {
                element = rc.getType();
            }
            MethodHandle getter;
            try {
                getter = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot access accessor for " + rc.getName(), e);
            }
            String protoName = rc.getName();
            String jsonName = Descriptors.toJsonName(protoName);
            FieldBinding fb = new FieldBinding(
                    pf.number(), pf.type(), repeated, pf.explicitPresence(),
                    pf.oneofGroup(), element, i, getter, protoName, jsonName,
                    mapKeyType, mapValueType, mapValueClass);
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
                constructor, components.length, unknownFieldsIndex, unknownFieldsGetter);
    }

    /** Parse une key JSON string en typed key selon mapKeyType. */
    private static Object parseMapKey(FieldType type, String s) throws IOException {
        return switch (type) {
            case BOOL -> Boolean.parseBoolean(s);
            case STRING -> s;
            case INT32, SINT32, SFIXED32 -> Integer.parseInt(s);
            case UINT32, FIXED32 -> {
                long raw = Long.parseLong(s);
                if (raw < 0L || raw > 0xFFFFFFFFL) throw new IOException("uint32 key out of range: " + s);
                yield (int) raw;
            }
            case INT64, SINT64, SFIXED64 -> Long.parseLong(s);
            case UINT64, FIXED64 -> Long.parseUnsignedLong(s);
            default -> throw new IOException("Unsupported map key type: " + type);
        };
    }

    /** Lit la value JSON selon mapValueType. */
    private static Object readMapValue(FieldType type, Class<?> messageClass,
                                       JsonParser parser, JsonParser.Event v) throws IOException {
        if (v == JsonParser.Event.VALUE_NULL) return null;
        return switch (type) {
            case BOOL -> v == JsonParser.Event.VALUE_TRUE;
            case INT32, SINT32, SFIXED32 -> parser.getInt();
            case UINT32, FIXED32 -> {
                long raw = v == JsonParser.Event.VALUE_STRING
                        ? Long.parseLong(parser.getString()) : parser.getLong();
                if (raw < 0L || raw > 0xFFFFFFFFL) throw new IOException("uint32 value out of range");
                yield (int) raw;
            }
            case INT64, SINT64, SFIXED64 -> {
                if (v == JsonParser.Event.VALUE_STRING) yield Long.parseLong(parser.getString());
                yield parser.getLong();
            }
            case UINT64, FIXED64 -> {
                if (v == JsonParser.Event.VALUE_STRING) yield Long.parseUnsignedLong(parser.getString());
                yield Long.parseUnsignedLong(parser.getValue().toString());
            }
            case FLOAT -> parser.getBigDecimal().floatValue();
            case DOUBLE -> parser.getBigDecimal().doubleValue();
            case STRING -> parser.getString();
            case BYTES -> java.util.Base64.getDecoder().decode(parser.getString());
            case ENUM -> {
                if (v == JsonParser.Event.VALUE_STRING) {
                    String name = parser.getString();
                    if (messageClass != null && messageClass.isEnum()) {
                        for (Object c : messageClass.getEnumConstants()) {
                            if (((Enum<?>) c).name().equals(name)) yield c;
                        }
                    }
                    yield null;
                }
                yield null;
            }
            case MESSAGE -> {
                if (messageClass == null) throw new IOException("Map value MESSAGE without class");
                if (v != JsonParser.Event.START_OBJECT) throw new IOException("Expected message object");
                yield readMessage(planFor(messageClass), parser);
            }
            case MAP -> throw new IOException("Nested MAP is forbidden");
        };
    }

    private static Class<?> resolveMapValue(RecordComponent rc) {
        Type generic = rc.getGenericType();
        if (generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length >= 2 && args[1] instanceof Class<?> c) return c;
        }
        return Object.class;
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
                           int componentCount,
                           int unknownFieldsIndex,
                           MethodHandle unknownFieldsGetter) {}

    static final class FieldBinding {
        final int number;
        final FieldType type;
        final boolean repeated;
        final boolean explicitPresence;
        final String oneofGroup;
        final Class<?> elementType;
        final int componentIndex;
        final MethodHandle getter;
        final String protoName;
        final String jsonName;
        final FieldType mapKeyType;
        final FieldType mapValueType;
        final Class<?> mapValueClass;

        FieldBinding(int number, FieldType type, boolean repeated, boolean explicitPresence,
                     String oneofGroup, Class<?> elementType, int componentIndex,
                     MethodHandle getter, String protoName, String jsonName) {
            this(number, type, repeated, explicitPresence, oneofGroup, elementType,
                 componentIndex, getter, protoName, jsonName, null, null, null);
        }

        FieldBinding(int number, FieldType type, boolean repeated, boolean explicitPresence,
                     String oneofGroup, Class<?> elementType, int componentIndex,
                     MethodHandle getter, String protoName, String jsonName,
                     FieldType mapKeyType, FieldType mapValueType, Class<?> mapValueClass) {
            this.number = number;
            this.type = type;
            this.repeated = repeated;
            this.explicitPresence = explicitPresence;
            this.oneofGroup = oneofGroup;
            this.elementType = elementType;
            this.componentIndex = componentIndex;
            this.getter = getter;
            this.protoName = protoName;
            this.jsonName = jsonName;
            this.mapKeyType = mapKeyType;
            this.mapValueType = mapValueType;
            this.mapValueClass = mapValueClass;
        }
    }
}
