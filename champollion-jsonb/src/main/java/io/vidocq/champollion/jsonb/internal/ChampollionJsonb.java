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
 * Main {@link Jsonb} implementation. M4.1:
 * <ul>
 *   <li>runtime write path via {@link RuntimeBindingRegistry}</li>
 *   <li>read path deferred to M4.2</li>
 * </ul>
 *
 * <p>No {@code synchronized} — caches use {@link ClassValue}.</p>
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

    /**
     * P9 — per-thread Champollion parser pool. Avoids reallocating
     * {@code JsonTokenizer + char[512] + Deque} on every {@code fromJson} call.
     * Pool only when the provider is {@code ChampollionJsonProvider}; for any
     * other provider, fall back to {@code createParser}.
     */
    private final ThreadLocal<io.vidocq.champollion.jsonp.internal.ChampollionJsonParser> parserPool =
            ThreadLocal.withInitial(() -> null);

    ChampollionJsonb(JsonbConfig config, JsonProvider jsonProvider, StaticBindings staticBindings) {
        this.config = config;
        this.jsonProvider = jsonProvider;
        this.prettyPrinting = booleanProp(config, JsonbConfig.FORMATTING);
        this.writeNullValues = booleanProp(config, JsonbConfig.NULL_VALUES);
        this.strictIJson = booleanProp(config, JsonbConfig.STRICT_IJSON);
        String defaultDateFormat = stringProp(config, JsonbConfig.DATE_FORMAT);
        // In strict IJSON mode, force BASE_64 (with padding) — §3.5.5.
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
        // In strict IJSON mode, the date/time format is fixed to the ZonedDateTime format — §3.5.1.
        // Pattern expected by the TCK: literal Z + numeric offset XXX (xxx = numeric
        // offset ±HH:MM, never "Z").
        String effectiveDateFormat = (this.strictIJson && defaultDateFormat == null)
                ? "yyyy-MM-dd'T'HH:mm:ss'Z'xxx"
                : defaultDateFormat;
        java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> adapterMap = collectAdapters(config);
        this.writeRegistry = new RuntimeBindingRegistry(effectiveDateFormat, this.writeNullValues, binaryStrategy,
                namingStrategy, orderStrategy, visibilityStrategy, configLocale, adapterMap);
        this.readRegistry = new RuntimeReadRegistry(defaultDateFormat, binaryStrategy, namingStrategy, visibilityStrategy, configLocale, failOnUnknown, creatorParametersRequired, adapterMap);
        this.writeRegistry.setGlobalSerializers(collectSerializers(config));
        this.readRegistry.setGlobalDeserializers(collectDeserializers(config));
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

    /**
     * Extrait les adapters globaux depuis {@code JsonbConfig.ADAPTERS} et indexe-les
     * by their {@code Original} type (the first generic parameter of {@code JsonbAdapter}).
     */
    @SuppressWarnings("rawtypes")
    private static java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> collectAdapters(JsonbConfig config) {
        if (config == null) return java.util.Map.of();
        var opt = config.getProperty(JsonbConfig.ADAPTERS);
        if (opt.isEmpty()) return java.util.Map.of();
        var arr = (jakarta.json.bind.adapter.JsonbAdapter[]) opt.get();
        java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> out = new java.util.LinkedHashMap<>();
        for (var ad : arr) {
            Class<?> orig = findAdapterOriginalType(ad.getClass());
            if (orig != null) out.put(orig, ad);
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private static Class<?> findAdapterOriginalType(Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass) {
        for (Class<?> c = adapterClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) {
                if (t instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == jakarta.json.bind.adapter.JsonbAdapter.class) {
                    java.lang.reflect.Type orig = pt.getActualTypeArguments()[0];
                    if (orig instanceof Class<?> oc) return oc;
                    if (orig instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> collectSerializers(JsonbConfig config) {
        if (config == null) return java.util.Map.of();
        var opt = config.getProperty(JsonbConfig.SERIALIZERS);
        if (opt.isEmpty()) return java.util.Map.of();
        var arr = (jakarta.json.bind.serializer.JsonbSerializer[]) opt.get();
        java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> out = new java.util.LinkedHashMap<>();
        for (var s : arr) {
            Class<?> handled = findGenericArg(s.getClass(), jakarta.json.bind.serializer.JsonbSerializer.class);
            if (handled != null) out.put(handled, s);
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private static java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbDeserializer> collectDeserializers(JsonbConfig config) {
        if (config == null) return java.util.Map.of();
        var opt = config.getProperty(JsonbConfig.DESERIALIZERS);
        if (opt.isEmpty()) return java.util.Map.of();
        var arr = (jakarta.json.bind.serializer.JsonbDeserializer[]) opt.get();
        java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbDeserializer> out = new java.util.LinkedHashMap<>();
        for (var d : arr) {
            Class<?> handled = findGenericArg(d.getClass(), jakarta.json.bind.serializer.JsonbDeserializer.class);
            if (handled != null) out.put(handled, d);
        }
        return out;
    }

    /** Looks up the first generic parameter of interface {@code iface} on the class (recursive over supers). */
    private static Class<?> findGenericArg(Class<?> impl, Class<?> iface) {
        for (Class<?> c = impl; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) {
                if (t instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() == iface) {
                    java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                    if (arg instanceof Class<?> ac) return ac;
                    if (arg instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
                }
            }
        }
        return null;
    }

    /** Immutable view of the resolved static bindings, for diagnostics and tests. */
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
        // §3.5 strict IJSON — top-level must be object or array, otherwise JsonbException.
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
        // Lookup-first: static binding available for this type?
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

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(String str, Class<T> type) {
        if (jsonProvider instanceof io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider) {
            return (T) readValuePooledFromString(str, type);
        }
        return fromJson(new StringReader(str), (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(String str, Type runtimeType) {
        if (jsonProvider instanceof io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider) {
            return (T) readValuePooledFromString(str, runtimeType);
        }
        return fromJson(new StringReader(str), runtimeType);
    }

    /**
     * P10.1 fast-path: parse a {@link String} directly without
     * {@link StringReader} or intermediate {@code char[]} (via
     * {@link io.vidocq.champollion.jsonp.internal.JsonStringTokenizer}).
     */
    private Object readValuePooledFromString(String src, Type runtimeType) {
        var parser = parserPool.get();
        if (parser == null) {
            parser = new io.vidocq.champollion.jsonp.internal.ChampollionJsonParser(src);
            parserPool.set(parser);
        } else {
            parser.reset(src);
        }
        return readValue(parser, runtimeType);
    }

    @Override public <T> T fromJson(Reader reader, Class<T> type) {
        return fromJson(reader, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(Reader reader, Type runtimeType) {
        if (jsonProvider instanceof io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider) {
            return (T) readValuePooled(reader, runtimeType);
        }
        try (JsonParser p = jsonProvider.createParser(reader)) {
            return (T) readValue(p, runtimeType);
        }
    }

    /**
     * Pooled path: retrieve (or create) a thread-local
     * {@link io.vidocq.champollion.jsonp.internal.ChampollionJsonParser},
     * {@code reset(reader)}, read the value, and leave it in place for the next
     * call on this thread. The provided {@code reader} is not closed — that is the
     * caller's responsibility (consistent with the usual
     * {@code fromJson(Reader, ...)} contract).
     */
    private Object readValuePooled(Reader reader, Type runtimeType) {
        var parser = parserPool.get();
        if (parser == null) {
            parser = new io.vidocq.champollion.jsonp.internal.ChampollionJsonParser(reader);
            parserPool.set(parser);
        } else {
            parser.reset(reader);
        }
        return readValue(parser, runtimeType);
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
        // Lookup-first: static binding for this type?
        JsonbBinding staticBinding = staticBindings.get(rawClassOf(runtimeType, null));
        if (staticBinding != null) {
            return staticBinding.read(p);
        }
        return readRegistry.readerFor(runtimeType).read(p);
    }

    @Override public void close() throws Exception { /* nothing held */ }
}
