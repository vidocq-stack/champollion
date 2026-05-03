package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.util.Map;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;

/**
 * Implémentation principale de {@link Jsonb}. M4.1 :
 * <ul>
 *   <li>écriture en mode runtime via {@link RuntimeBindingRegistry}</li>
 *   <li>lecture reportée à M4.2</li>
 * </ul>
 *
 * <p>Pas de {@code synchronized} — les caches utilisent {@link ClassValue}.</p>
 */
public final class ChampollionJsonb implements Jsonb {

    private final JsonbConfig config;
    private final JsonProvider jsonProvider;
    private final RuntimeBindingRegistry writeRegistry;
    private final RuntimeReadRegistry readRegistry;
    private final StaticBindings staticBindings;
    private final boolean prettyPrinting;
    private final boolean writeNullValues;
    private final boolean strictIJson;

    ChampollionJsonb(JsonbConfig config, JsonProvider jsonProvider, StaticBindings staticBindings) {
        this.config = config;
        this.jsonProvider = jsonProvider;
        this.prettyPrinting = booleanProp(config, JsonbConfig.FORMATTING);
        this.writeNullValues = booleanProp(config, JsonbConfig.NULL_VALUES);
        this.strictIJson = booleanProp(config, JsonbConfig.STRICT_IJSON);
        String defaultDateFormat = stringProp(config, JsonbConfig.DATE_FORMAT);
        // En mode IJSON strict, on force BASE_64 (avec padding) — §3.5.5.
        String binaryStrategy = this.strictIJson
                ? jakarta.json.bind.config.BinaryDataStrategy.BASE_64
                : stringProp(config, JsonbConfig.BINARY_DATA_STRATEGY);
        String namingStrategy = stringProp(config, JsonbConfig.PROPERTY_NAMING_STRATEGY);
        String orderStrategy = stringProp(config, JsonbConfig.PROPERTY_ORDER_STRATEGY);
        var visibilityStrategy = (jakarta.json.bind.config.PropertyVisibilityStrategy)
                config.getProperty(JsonbConfig.PROPERTY_VISIBILITY_STRATEGY).orElse(null);
        var configLocale = (java.util.Locale) config.getProperty(JsonbConfig.LOCALE).orElse(null);
        boolean failOnUnknown = booleanProp(config, "jsonb.fail-on-unknown-properties");
        boolean creatorParametersRequired = booleanProp(config, JsonbConfig.CREATOR_PARAMETERS_REQUIRED);
        // En mode IJSON strict, le format date/time est figé sur le format ZonedDateTime — §3.5.1.
        // Pattern attendu par TCK : Z littéral + offset numérique XXX (xxx = offset always
        // numerique ±HH:MM, jamais "Z").
        String effectiveDateFormat = (this.strictIJson && defaultDateFormat == null)
                ? "yyyy-MM-dd'T'HH:mm:ss'Z'xxx"
                : defaultDateFormat;
        this.writeRegistry = new RuntimeBindingRegistry(effectiveDateFormat, this.writeNullValues, binaryStrategy,
                namingStrategy, orderStrategy, visibilityStrategy, configLocale);
        this.readRegistry = new RuntimeReadRegistry(defaultDateFormat, binaryStrategy, namingStrategy, visibilityStrategy, configLocale, failOnUnknown, creatorParametersRequired);
        this.staticBindings = staticBindings == null ? StaticBindings.EMPTY : staticBindings;
    }

    private static boolean booleanProp(JsonbConfig config, String key) {
        if (config == null) return false;
        return config.getProperty(key).map(o -> Boolean.TRUE.equals(o) || "true".equals(o)).orElse(false);
    }

    private static String stringProp(JsonbConfig config, String key) {
        if (config == null) return null;
        return config.getProperty(key).map(Object::toString).orElse(null);
    }

    /** Vue immuable des bindings statiques résolus, pour diagnostic et tests. */
    public Map<Class<?>, JsonbBinding<?>> staticBindingsView() {
        return staticBindings.view();
    }

    // ===== toJson =====

    @Override public String toJson(Object object) {
        if (object == null) return "null";
        return toJson(object, object.getClass());
    }

    @Override public String toJson(Object object, Type runtimeType) {
        var sw = new StringWriter();
        toJson(object, runtimeType, sw);
        return sw.toString();
    }

    @Override public void toJson(Object object, Writer writer) {
        toJson(object, object == null ? Object.class : object.getClass(), writer);
    }

    @Override public void toJson(Object object, Type runtimeType, Writer writer) {
        try (JsonGenerator g = createGenerator(writer)) {
            writeValue(g, object, runtimeType);
        }
    }

    private JsonGenerator createGenerator(Writer writer) {
        if (prettyPrinting) {
            return jsonProvider.createGeneratorFactory(
                    java.util.Map.of(JsonGenerator.PRETTY_PRINTING, true)).createGenerator(writer);
        }
        return jsonProvider.createGenerator(writer);
    }

    private JsonGenerator createGenerator(OutputStream stream) {
        if (prettyPrinting) {
            return jsonProvider.createGeneratorFactory(
                    java.util.Map.of(JsonGenerator.PRETTY_PRINTING, true)).createGenerator(stream);
        }
        return jsonProvider.createGenerator(stream);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void writeValue(JsonGenerator g, Object object, Type runtimeType) {
        if (object == null) { g.writeNull(); return; }
        // §3.5 IJSON strict — top-level doit être objet ou tableau, sinon JsonbException.
        if (strictIJson) {
            Class<?> raw = rawClassOf(runtimeType, object);
            boolean okTop = java.util.Map.class.isAssignableFrom(raw)
                    || java.util.Collection.class.isAssignableFrom(raw)
                    || raw.isArray()
                    || jakarta.json.JsonObject.class.isAssignableFrom(raw)
                    || jakarta.json.JsonArray.class.isAssignableFrom(raw)
                    || (!raw.isPrimitive()
                        && !CharSequence.class.isAssignableFrom(raw)
                        && !Number.class.isAssignableFrom(raw)
                        && !Boolean.class.isAssignableFrom(raw)
                        && !Character.class.isAssignableFrom(raw)
                        && !raw.isEnum()
                        && !RuntimeBindingRegistry.isDateLikeType(raw));
            if (!okTop) {
                throw new JsonbException("IJSON strict mode: top-level JSON text must be an object or array (got " + raw.getName() + ")");
            }
        }
        // Lookup-first : binding statique disponible pour ce type ?
        JsonbBinding staticBinding = staticBindings.get(rawClassOf(runtimeType, object));
        if (staticBinding != null) {
            staticBinding.write(g, object);
            return;
        }
        BindingWriter w = writeRegistry.writerFor(runtimeType);
        w.write(g, object);
    }

    private static Class<?> rawClassOf(Type t, Object instance) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType p) return (Class<?>) p.getRawType();
        return instance == null ? Object.class : instance.getClass();
    }

    @Override public void toJson(Object object, OutputStream stream) {
        toJson(object, object == null ? Object.class : object.getClass(), stream);
    }

    @Override public void toJson(Object object, Type runtimeType, OutputStream stream) {
        try (JsonGenerator g = createGenerator(stream)) {
            writeValue(g, object, runtimeType);
        }
    }

    // ===== fromJson =====

    @Override public <T> T fromJson(String str, Class<T> type) {
        return fromJson(new StringReader(str), (Type) type);
    }

    @Override public <T> T fromJson(String str, Type runtimeType) {
        return fromJson(new StringReader(str), runtimeType);
    }

    @Override public <T> T fromJson(Reader reader, Class<T> type) {
        return fromJson(reader, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(Reader reader, Type runtimeType) {
        try (JsonParser p = jsonProvider.createParser(reader)) {
            return (T) readValue(p, runtimeType);
        }
    }

    @Override public <T> T fromJson(InputStream stream, Class<T> type) {
        return fromJson(stream, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(InputStream stream, Type runtimeType) {
        try (JsonParser p = jsonProvider.createParser(stream)) {
            return (T) readValue(p, runtimeType);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object readValue(JsonParser p, Type runtimeType) {
        // Lookup-first : binding statique pour ce type ?
        JsonbBinding staticBinding = staticBindings.get(rawClassOf(runtimeType, null));
        if (staticBinding != null) {
            return staticBinding.read(p);
        }
        return readRegistry.readerFor(runtimeType).read(p);
    }

    @Override public void close() throws Exception { /* nothing held */ }
}
