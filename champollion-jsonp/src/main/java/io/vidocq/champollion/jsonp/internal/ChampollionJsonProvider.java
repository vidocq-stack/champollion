package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Implémentation Champollion de {@link JsonProvider}. Point d'entrée principal de
 * Jakarta JSON-P 2.1 : tout passe par {@code Json.create*} qui délègue à ce provider
 * via le ServiceLoader.
 *
 * <p>Décodage des {@link InputStream} : RFC 8259 §8.1 — UTF-8 par défaut, sans BOM.
 * Le BOM est laissé à la charge de la lecture si présent (le scanner le rejettera
 * comme caractère invalide à la racine, conformément à la spec).</p>
 */
public final class ChampollionJsonProvider extends JsonProvider {

    /** Facteurs de configuration. M1.4 : minimal — pretty printing. À enrichir au fil de l'eau. */
    public static final String PRETTY_PRINTING = JsonGenerator.PRETTY_PRINTING;

    public ChampollionJsonProvider() {}

    private static boolean prettyOf(Map<String, ?> config) {
        if (config == null) return false;
        Object v = config.get(PRETTY_PRINTING);
        return Boolean.TRUE.equals(v) || "true".equals(v);
    }

    private static Reader utf8Reader(InputStream in) {
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Writer utf8Writer(OutputStream out) {
        return new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    // ===== Parser =====

    @Override public JsonParser createParser(Reader reader) {
        return new ChampollionJsonParser(reader);
    }

    @Override public JsonParser createParser(InputStream in) {
        return new ChampollionJsonParser(utf8Reader(in));
    }

    @Override public JsonParserFactory createParserFactory(Map<String, ?> config) {
        Map<String, ?> snapshot = config == null ? Collections.emptyMap() : Map.copyOf(config);
        return new JsonParserFactory() {
            @Override public JsonParser createParser(Reader reader) { return new ChampollionJsonParser(reader); }
            @Override public JsonParser createParser(InputStream in) { return new ChampollionJsonParser(utf8Reader(in)); }
            @Override public JsonParser createParser(InputStream in, Charset charset) {
                return new ChampollionJsonParser(new InputStreamReader(in, charset));
            }
            @Override public JsonParser createParser(JsonObject obj) {
                throw new UnsupportedOperationException("createParser(JsonObject) — implemented in M3");
            }
            @Override public JsonParser createParser(JsonArray arr) {
                throw new UnsupportedOperationException("createParser(JsonArray) — implemented in M3");
            }
            @Override public Map<String, ?> getConfigInUse() { return snapshot; }
        };
    }

    // ===== Generator =====

    @Override public JsonGenerator createGenerator(Writer writer) {
        return new ChampollionJsonGenerator(writer, /*pretty*/ false);
    }

    @Override public JsonGenerator createGenerator(OutputStream out) {
        return new ChampollionJsonGenerator(utf8Writer(out), /*pretty*/ false);
    }

    @Override public JsonGeneratorFactory createGeneratorFactory(Map<String, ?> config) {
        boolean pretty = prettyOf(config);
        Map<String, ?> snapshot = config == null ? Collections.emptyMap() : Map.copyOf(config);
        return new JsonGeneratorFactory() {
            @Override public JsonGenerator createGenerator(Writer writer) {
                return new ChampollionJsonGenerator(writer, pretty);
            }
            @Override public JsonGenerator createGenerator(OutputStream out) {
                return new ChampollionJsonGenerator(utf8Writer(out), pretty);
            }
            @Override public JsonGenerator createGenerator(OutputStream out, Charset charset) {
                return new ChampollionJsonGenerator(new OutputStreamWriter(out, charset), pretty);
            }
            @Override public Map<String, ?> getConfigInUse() { return snapshot; }
        };
    }

    // ===== Reader / Writer (object model) =====

    @Override public JsonReader createReader(Reader reader) {
        return new ChampollionJsonReader(reader);
    }

    @Override public JsonReader createReader(InputStream in) {
        return new ChampollionJsonReader(utf8Reader(in));
    }

    @Override public JsonReaderFactory createReaderFactory(Map<String, ?> config) {
        Map<String, ?> snapshot = config == null ? Collections.emptyMap() : Map.copyOf(config);
        return new JsonReaderFactory() {
            @Override public JsonReader createReader(Reader reader) { return new ChampollionJsonReader(reader); }
            @Override public JsonReader createReader(InputStream in) { return new ChampollionJsonReader(utf8Reader(in)); }
            @Override public JsonReader createReader(InputStream in, Charset charset) {
                return new ChampollionJsonReader(new InputStreamReader(in, charset));
            }
            @Override public Map<String, ?> getConfigInUse() { return snapshot; }
        };
    }

    @Override public JsonWriter createWriter(Writer writer) {
        return new ChampollionJsonWriter(writer, /*pretty*/ false);
    }

    @Override public JsonWriter createWriter(OutputStream out) {
        return new FlushingJsonWriter(new ChampollionJsonWriter(utf8Writer(out), /*pretty*/ false), out);
    }

    @Override public JsonWriterFactory createWriterFactory(Map<String, ?> config) {
        boolean pretty = prettyOf(config);
        Map<String, ?> snapshot = config == null ? Collections.emptyMap() : Map.copyOf(config);
        return new JsonWriterFactory() {
            @Override public JsonWriter createWriter(Writer writer) {
                return new ChampollionJsonWriter(writer, pretty);
            }
            @Override public JsonWriter createWriter(OutputStream out) {
                return new FlushingJsonWriter(new ChampollionJsonWriter(utf8Writer(out), pretty), out);
            }
            @Override public JsonWriter createWriter(OutputStream out, Charset charset) {
                return new FlushingJsonWriter(
                        new ChampollionJsonWriter(new OutputStreamWriter(out, charset), pretty), out);
            }
            @Override public Map<String, ?> getConfigInUse() { return snapshot; }
        };
    }

    // ===== Builders =====

    @Override public JsonObjectBuilder createObjectBuilder() {
        return new ChampollionJsonObjectBuilder();
    }

    @Override public JsonObjectBuilder createObjectBuilder(JsonObject object) {
        var b = new ChampollionJsonObjectBuilder();
        object.forEach(b::add);
        return b;
    }

    @Override public JsonObjectBuilder createObjectBuilder(Map<String, ?> map) {
        var b = new ChampollionJsonObjectBuilder();
        if (map == null) return b;
        for (var e : map.entrySet()) {
            if (e.getKey() == null) throw new NullPointerException("null key not allowed in JSON object");
            b.add(e.getKey(), toJsonValue(e.getValue()));
        }
        return b;
    }

    /** Convertit une valeur arbitraire (récupérée d'une Map) en JsonValue. */
    private static JsonValue toJsonValue(Object v) {
        if (v == null) return JsonValue.NULL;
        if (v instanceof JsonValue jv) return jv;
        if (v instanceof String s) return new ChampollionJsonString(s);
        if (v instanceof Boolean b) return b ? JsonValue.TRUE : JsonValue.FALSE;
        if (v instanceof Integer i) return ChampollionJsonNumber.of(i);
        if (v instanceof Long l) return ChampollionJsonNumber.of(l);
        if (v instanceof java.math.BigDecimal bd) return ChampollionJsonNumber.of(bd);
        if (v instanceof java.math.BigInteger bi) return ChampollionJsonNumber.of(new java.math.BigDecimal(bi));
        if (v instanceof Number n) return ChampollionJsonNumber.of(java.math.BigDecimal.valueOf(n.doubleValue()));
        if (v instanceof Map<?, ?> m) {
            var ob = new ChampollionJsonObjectBuilder();
            for (var e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new IllegalArgumentException("nested Map keys must be String");
                }
                ob.add(k, toJsonValue(e.getValue()));
            }
            return ob.build();
        }
        if (v instanceof java.util.Collection<?> c) {
            var ab = new ChampollionJsonArrayBuilder();
            for (Object x : c) ab.add(toJsonValue(x));
            return ab.build();
        }
        throw new IllegalArgumentException("Unsupported value type for JSON conversion: " + v.getClass());
    }

    @Override public JsonArrayBuilder createArrayBuilder() {
        return new ChampollionJsonArrayBuilder();
    }

    @Override public JsonArrayBuilder createArrayBuilder(JsonArray array) {
        var b = new ChampollionJsonArrayBuilder();
        for (JsonValue v : array) b.add(v);
        return b;
    }

    @Override public JsonArrayBuilder createArrayBuilder(java.util.Collection<?> collection) {
        var b = new ChampollionJsonArrayBuilder();
        if (collection == null) return b;
        for (Object v : collection) b.add(toJsonValue(v));
        return b;
    }

    // ===== Scalar value factories =====

    @Override public jakarta.json.JsonString createValue(String value) {
        return new ChampollionJsonString(value);
    }

    @Override public jakarta.json.JsonNumber createValue(int value) {
        return ChampollionJsonNumber.of(value);
    }

    @Override public jakarta.json.JsonNumber createValue(long value) {
        return ChampollionJsonNumber.of(value);
    }

    @Override public jakarta.json.JsonNumber createValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        return ChampollionJsonNumber.of(java.math.BigDecimal.valueOf(value));
    }

    @Override public jakarta.json.JsonNumber createValue(java.math.BigDecimal value) {
        return ChampollionJsonNumber.of(value);
    }

    @Override public jakarta.json.JsonNumber createValue(java.math.BigInteger value) {
        return ChampollionJsonNumber.of(new java.math.BigDecimal(value));
    }

    // ===== JsonPointer / JsonPatch =====

    @Override public jakarta.json.JsonPointer createPointer(String jsonPointer) {
        return new ChampollionJsonPointer(jsonPointer);
    }

    @Override public jakarta.json.JsonPatchBuilder createPatchBuilder() {
        return new ChampollionJsonPatchBuilder();
    }

    @Override public jakarta.json.JsonPatchBuilder createPatchBuilder(JsonArray array) {
        return new ChampollionJsonPatchBuilder(array);
    }

    @Override public jakarta.json.JsonPatch createPatch(JsonArray array) {
        return new ChampollionJsonPatch(array);
    }

    @Override public jakarta.json.JsonPatch createDiff(JsonStructure source, JsonStructure target) {
        return ChampollionJsonPatchDiff.diff(source, target);
    }

    @Override public jakarta.json.JsonMergePatch createMergePatch(JsonValue patch) {
        return new ChampollionJsonMergePatch(patch);
    }

    @Override public jakarta.json.JsonMergePatch createMergeDiff(JsonValue source, JsonValue target) {
        return ChampollionJsonMergePatch.diff(source, target);
    }

    @Override public JsonBuilderFactory createBuilderFactory(Map<String, ?> config) {
        Map<String, ?> snapshot = config == null ? Collections.emptyMap() : Map.copyOf(config);
        return new JsonBuilderFactory() {
            @Override public JsonObjectBuilder createObjectBuilder() { return new ChampollionJsonObjectBuilder(); }
            @Override public JsonArrayBuilder createArrayBuilder() { return new ChampollionJsonArrayBuilder(); }
            @Override public Map<String, ?> getConfigInUse() { return snapshot; }
        };
    }

    /**
     * Wrapper qui flush l'OutputStream sous-jacent après écriture, parce que les
     * {@link OutputStreamWriter} bufferisent sans flush automatique côté byte stream.
     * Sans cela, {@code ByteArrayOutputStream} retournerait une chaîne vide après close().
     */
    private record FlushingJsonWriter(ChampollionJsonWriter delegate, OutputStream out) implements JsonWriter {
        @Override public void writeArray(JsonArray array) {
            delegate.writeArray(array);
            tryFlush();
        }
        @Override public void writeObject(JsonObject object) {
            delegate.writeObject(object);
            tryFlush();
        }
        @Override public void write(jakarta.json.JsonStructure value) {
            delegate.write(value);
            tryFlush();
        }
        @Override public void write(JsonValue value) {
            delegate.write(value);
            tryFlush();
        }
        @Override public void close() {
            delegate.close();
            tryFlush();
        }
        private void tryFlush() {
            try { out.flush(); } catch (IOException e) { throw new JsonException("I/O error flushing", e); }
        }
    }
}
