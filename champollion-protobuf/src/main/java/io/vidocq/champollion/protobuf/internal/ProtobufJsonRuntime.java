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
        // Well-Known Types : encodage canonical spécifique (non objet JSON).
        if (writeWktTopLevel(message, gen)) return;
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
            } else if (!fb.explicitPresence && isDefault(fb, value)) {
                continue; // proto3 implicit presence
            } else {
                writeScalar(fb, value, gen, fb.jsonName);
            }
        }
        gen.writeEnd();
    }

    private static void writeScalar(FieldBinding fb, Object value, JsonGenerator gen, String key) {
        switch (fb.type) {
            case INT32, SINT32, SFIXED32 -> {
                int v = (int) value;
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case UINT32, FIXED32 -> {
                // Proto3 JSON canonical : uint32 doit toujours apparaître comme
                // entier non-négatif — un Java int "négatif" (= valeur > 2^31)
                // doit être promu à long unsigned.
                long v = Integer.toUnsignedLong((int) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case INT64, SINT64, SFIXED64 -> {
                // Proto3 canonical : int64 signé toujours en string.
                String v = Long.toString((long) value);
                if (key != null) gen.write(key, v); else gen.write(v);
            }
            case UINT64, FIXED64 -> {
                // Proto3 canonical : uint64 non-signé toujours en string,
                // en représentation unsigned.
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
                // WKT en champ imbriqué : émettre la valeur canonical à plat (string/number).
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
     * Émet le message {@code message} comme top-level d'un document JSON s'il
     * s'agit d'un WKT. Retourne {@code true} si géré.
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
        return writeWrapperTopLevel(message, gen);
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
        return writeWrapperAsValue(v, gen, key);
    }

    // ============================================================ Any JSON canonical

    /**
     * Sérialise un {@link Any} selon le canonical mapping :
     * <ul>
     *   <li>Si le type wrappé est un WKT (Timestamp, Duration, etc.) →
     *       {@code {"@type":"...", "value":<wkt-form>}}</li>
     *   <li>Sinon → {@code {"@type":"...", ...champs aplatis}}</li>
     *   <li>Type inconnu → fallback dégradé
     *       {@code {"@type":"...", "value":"<base64>"}} (non strictement
     *       canonical mais utile pour la traversée opaque)</li>
     * </ul>
     */
    private static void writeAny(Any any, JsonGenerator gen, String key) {
        String typeUrl = any.type_url();
        String fullName = TypeRegistry.fullNameFromTypeUrl(typeUrl);
        Class<?> type = TypeRegistry.lookup(fullName);

        if (key != null) gen.writeStartObject(key);
        else gen.writeStartObject();
        gen.write("@type", typeUrl);

        if (type == null) {
            // Fallback : type inconnu — émet la valeur base64.
            gen.write("value", java.util.Base64.getEncoder().encodeToString(any.value()));
            gen.writeEnd();
            return;
        }

        // Décoder la valeur dans le type concret.
        Object wrapped;
        try {
            wrapped = Protobuf.parser(type).parseFrom(any.value());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Cannot decode Any.value as " + type, e);
        }

        if (isWktClass(type) && type != Empty.class) {
            // WKT : un seul champ "value" avec le format canonical du WKT.
            writeWktAsValue(wrapped, gen, "value");
        } else if (type == Empty.class) {
            // Empty wrappé : un seul champ "value": {}
            gen.writeStartObject("value").writeEnd();
        } else {
            // Message ordinaire : aplatir les champs au même niveau que @type.
            JsonBindingPlan plan = planFor(type);
            writeMessageBody(plan, wrapped, gen);
        }
        gen.writeEnd();
    }

    /**
     * Lit un {@link Any} depuis un {@link JsonParser}. L'event {@code v}
     * passé en paramètre est le {@code START_OBJECT} qui débute l'objet Any.
     * On bufferise tout l'objet dans un {@link JsonObject} DOM pour
     * pouvoir lire {@code @type} avant le reste — JSON est non-ordonné.
     */
    private static Any readAny(JsonParser parser) throws java.io.IOException {
        JsonObject obj = parser.getObject();
        if (!obj.containsKey("@type")) {
            throw new java.io.IOException("Any object must contain @type");
        }
        String typeUrl = obj.getString("@type");
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
            // WKT : un seul champ "value" à parser via la mécanique WKT
            JsonValue v = obj.get("value");
            if (v == null) {
                if (type == Empty.class) {
                    valueBytes = new byte[0];
                } else {
                    throw new java.io.IOException("Any/" + fullName + " missing required 'value'");
                }
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

    private static String formatTimestamp(Timestamp t) {
        Instant instant = t.toInstant();
        // RFC 3339 / ISO-8601 — fraction nanoseconde optionnelle, toujours suffixe Z.
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static String formatDuration(Duration d) {
        long sec = d.seconds();
        int nanos = d.nanos();
        StringBuilder sb = new StringBuilder();
        // Représentation négative : signe sur seconds OU nanos (proto garantit même signe).
        boolean negative = sec < 0 || nanos < 0;
        if (negative) {
            sb.append('-');
            sec = -sec;
            nanos = -nanos;
        }
        sb.append(sec);
        if (nanos > 0) {
            sb.append('.');
            // Précision adaptative : 3, 6 ou 9 chiffres selon ce qui est nécessaire.
            String s = String.format("%09d", nanos);
            int trim = s.length();
            while (trim > 3 && s.charAt(trim - 1) == '0') trim--;
            // Arrondi à un multiple de 3 (3, 6, ou 9 chiffres canonical).
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
            // Conversion snake_case → camelCase sur chaque segment du path.
            String[] segments = path.split("\\.");
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(Descriptors.toJsonName(segments[i]));
            }
        }
        return sb.toString();
    }

    private static Timestamp parseTimestamp(String s) {
        Instant instant = DateTimeFormatter.ISO_INSTANT.parse(s, Instant::from);
        return Timestamp.from(instant);
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
            // Padder à 9 chiffres ou tronquer si plus long (les 9 premiers).
            if (frac.length() > 9) frac = frac.substring(0, 9);
            else while (frac.length() < 9) frac += "0";
            nanos = Integer.parseInt(frac);
        }
        if (negative) { sec = -sec; nanos = -nanos; }
        return new Duration(sec, nanos);
    }

    private static FieldMask parseFieldMask(String s) {
        if (s.isEmpty()) return new FieldMask(List.of());
        // Conversion inverse camelCase → snake_case sur chaque segment des paths.
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
        try (JsonParser parser = Json.createParser(new StringReader(json))) {
            if (!parser.hasNext()) {
                throw new IOException("Empty JSON for " + type.getSimpleName());
            }
            JsonParser.Event first = parser.next();
            // WKT : la représentation top-level n'est pas un objet (sauf Empty).
            if (isWktClass(type) && type != Empty.class) {
                return (T) readWktValue(type, parser, first);
            }
            if (first != JsonParser.Event.START_OBJECT) {
                throw new IOException("Expected JSON object for " + type.getSimpleName() + ", got " + first);
            }
            if (type == Empty.class) {
                // Consommer le END_OBJECT et retourner singleton.
                while (parser.hasNext() && parser.next() != JsonParser.Event.END_OBJECT) {
                    // skip champs inattendus
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
            case INT32, SINT32, SFIXED32 -> {
                if (v == JsonParser.Event.VALUE_NUMBER) yield parser.getInt();
                if (v == JsonParser.Event.VALUE_STRING) yield Integer.parseInt(parser.getString());
                throw new IOException("Expected int32-compatible JSON value, got " + v);
            }
            case UINT32, FIXED32 -> {
                // Accepte les valeurs [0, 2^32-1] et stocke en int signed (bit-preserving).
                long raw;
                if (v == JsonParser.Event.VALUE_NUMBER) raw = parser.getLong();
                else if (v == JsonParser.Event.VALUE_STRING) raw = Long.parseLong(parser.getString());
                else throw new IOException("Expected uint32-compatible JSON value, got " + v);
                if (raw < 0L || raw > 0xFFFFFFFFL) {
                    throw new IOException("uint32 out of range: " + raw);
                }
                yield (int) raw;
            }
            case INT64, SINT64, SFIXED64 -> {
                if (v == JsonParser.Event.VALUE_STRING) yield Long.parseLong(parser.getString());
                if (v == JsonParser.Event.VALUE_NUMBER) yield parser.getLong();
                throw new IOException("Expected int64-compatible JSON value, got " + v);
            }
            case UINT64, FIXED64 -> {
                // Accepte les valeurs [0, 2^64-1] et stocke en long signed (bit-preserving).
                if (v == JsonParser.Event.VALUE_STRING) {
                    yield Long.parseUnsignedLong(parser.getString());
                }
                if (v == JsonParser.Event.VALUE_NUMBER) {
                    // JsonNumber peut représenter de gros entiers via BigInteger,
                    // mais getLong() saturera/throw selon impl. On passe par la string.
                    String s = parser.getValue().toString();
                    yield Long.parseUnsignedLong(s);
                }
                throw new IOException("Expected uint64-compatible JSON value, got " + v);
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
                // WKT imbriqué : la valeur est un scalaire (string/number/bool) sauf Empty.
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
            lookup = MethodHandles.publicLookup();
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
                    pf.number(), pf.type(), repeated, pf.explicitPresence(),
                    element, i, getter, protoName, jsonName);
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
        final boolean explicitPresence;
        final Class<?> elementType;
        final int componentIndex;
        final MethodHandle getter;
        final String protoName;
        final String jsonName;

        FieldBinding(int number, FieldType type, boolean repeated, boolean explicitPresence,
                     Class<?> elementType, int componentIndex, MethodHandle getter,
                     String protoName, String jsonName) {
            this.number = number;
            this.type = type;
            this.repeated = repeated;
            this.explicitPresence = explicitPresence;
            this.elementType = elementType;
            this.componentIndex = componentIndex;
            this.getter = getter;
            this.protoName = protoName;
            this.jsonName = jsonName;
        }
    }
}
