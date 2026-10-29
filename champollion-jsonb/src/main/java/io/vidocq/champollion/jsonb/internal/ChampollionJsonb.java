/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.util.ArrayList;
import java.util.List;
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

    private static final System.Logger LOG = System.getLogger(ChampollionJsonb.class.getName());

    private final JsonProvider jsonProvider;
    private final RuntimeBindingRegistry writeRegistry;
    private final RuntimeReadRegistry readRegistry;
    private final StaticBindings staticBindings;
    private final boolean prettyPrinting;
    private final boolean writeNullValues;
    private final boolean strictIJson;

    /**
     * P9 — the Champollion parser kept between two reads. Avoids reallocating
     * {@code JsonTokenizer + char[512] + Deque} on every {@code fromJson} call.
     * Pool only when the provider is {@code ChampollionJsonProvider}; for any
     * other provider, fall back to {@code createParser}.
     * <p>
     * A read takes the parser out of the slot and puts it back when it is done, so a read that starts
     * meanwhile — on another thread, or from an adapter of this very read — finds the slot empty and uses
     * a parser of its own (BUG-20261029-07). Not a {@code ThreadLocal}: a virtual thread reads once and
     * ends, and a per-thread parser would never be reused (architecture constraint 4).
     */
    private final java.util.concurrent.atomic.AtomicReference<io.vidocq.champollion.jsonp.internal.ChampollionJsonParser>
            parserSlot = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * The generator buffer kept between two writes to a stream. A document that outgrows the small buffer a
     * generator starts with borrows it, and gives it back when the generator is closed: a list of a hundred
     * records no longer grows a buffer from 128 bytes to 4 KB, copying it at each step.
     */
    private final java.util.concurrent.atomic.AtomicReference<byte[]> writeBufferSlot =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * The bytes of a request body kept between two reads of a stream. A body that fits is read whole, decoded
     * to a string and parsed in place, with the kept parser: no decoder, no reader, no character buffer.
     */
    private final java.util.concurrent.atomic.AtomicReference<byte[]> readBufferSlot =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Largest body read whole; a longer one is streamed. */
    private static final int READ_BUFFER_SIZE = 8192;

    ChampollionJsonb(JsonbConfig config, JsonProvider jsonProvider, StaticBindings staticBindings) {
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
        var adapterMap = collectAdapters(config);
        this.writeRegistry = new RuntimeBindingRegistry(effectiveDateFormat, this.writeNullValues, binaryStrategy,
                namingStrategy, orderStrategy, visibilityStrategy, configLocale);
        this.writeRegistry.setGlobalAdapters(adapterMap);
        this.readRegistry = new RuntimeReadRegistry(defaultDateFormat, binaryStrategy, namingStrategy, visibilityStrategy, configLocale, failOnUnknown, creatorParametersRequired);
        this.readRegistry.setGlobalAdapters(adapterMap);
        var serializers = collectSerializers(config);
        var deserializers = collectDeserializers(config);
        this.writeRegistry.setGlobalSerializers(serializers);
        this.readRegistry.setGlobalDeserializers(deserializers);
        // A generated binding implements the default mapping of its type: under a configuration that changes the
        // mapping, the runtime registries serve every type, the generated bindings are not consulted.
        List<String> customised = mappingSettings(config, !adapterMap.isEmpty(), !serializers.isEmpty(),
                !deserializers.isEmpty());
        StaticBindings generated = staticBindings == null ? StaticBindings.EMPTY : staticBindings;
        if (!customised.isEmpty() && !generated.view().isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Generated JSON-B bindings not used: the configuration sets "
                    + customised + ", which they do not implement; the runtime mapping serves every type.");
            generated = StaticBindings.EMPTY;
        }
        this.staticBindings = generated;
        this.writeRegistry.setStaticBindings(this.staticBindings);
    }

    /**
     * The settings of {@code config} that change the default mapping, which generated bindings implement: empty when
     * they may serve their types. Formatting and encoding act on the generator and are not listed.
     */
    private static List<String> mappingSettings(JsonbConfig config, boolean adapters, boolean serializers,
                                                boolean deserializers) {
        List<String> settings = new ArrayList<>();
        for (String key : List.of(JsonbConfig.NULL_VALUES, JsonbConfig.STRICT_IJSON,
                JsonbConfig.CREATOR_PARAMETERS_REQUIRED, "jsonb.fail-on-unknown-properties")) {
            if (booleanProp(config, key)) settings.add(key);
        }
        for (String key : List.of(JsonbConfig.DATE_FORMAT, JsonbConfig.BINARY_DATA_STRATEGY, JsonbConfig.LOCALE,
                JsonbConfig.PROPERTY_VISIBILITY_STRATEGY)) {
            if (config != null && config.getProperty(key).isPresent()) settings.add(key);
        }
        String naming = stringProp(config, JsonbConfig.PROPERTY_NAMING_STRATEGY);
        if (naming != null && !jakarta.json.bind.config.PropertyNamingStrategy.IDENTITY.equals(naming)) {
            settings.add(JsonbConfig.PROPERTY_NAMING_STRATEGY);
        }
        String order = stringProp(config, JsonbConfig.PROPERTY_ORDER_STRATEGY);
        if (order != null && !jakarta.json.bind.config.PropertyOrderStrategy.LEXICOGRAPHICAL.equals(order)) {
            settings.add(JsonbConfig.PROPERTY_ORDER_STRATEGY);
        }
        if (adapters) settings.add(JsonbConfig.ADAPTERS);
        if (serializers) settings.add(JsonbConfig.SERIALIZERS);
        if (deserializers) settings.add(JsonbConfig.DESERIALIZERS);
        return settings;
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
            Class<?> orig = findGenericArg(ad.getClass(), jakarta.json.bind.adapter.JsonbAdapter.class);
            if (orig != null) out.put(orig, ad);
        }
        return out;
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
                    return rawClassOf(pt.getActualTypeArguments()[0]);
                }
            }
        }
        return null;
    }

    /** Raw class of a type argument: the class itself, the raw type of a parameterized type, {@code null} otherwise. */
    private static Class<?> rawClassOf(java.lang.reflect.Type arg) {
        if (arg instanceof Class<?> ac) return ac;
        if (arg instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
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
        if (prettyPrinting || !(jsonProvider instanceof io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider)) {
            try (JsonGenerator g = createGenerator(stream)) {
                writeValue(g, object, runtimeType);
            }
            return;
        }
        try (JsonGenerator g = new io.vidocq.champollion.jsonp.internal.ChampollionJsonGenerator(stream, false, writeBufferSlot)) {
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
        var parser = parserSlot.getAndSet(null);
        if (parser == null) {
            parser = new io.vidocq.champollion.jsonp.internal.ChampollionJsonParser(src).keepingKeys();
        } else {
            parser.reset(src);
        }
        try {
            return readValue(parser, runtimeType);
        } finally {
            parserSlot.compareAndSet(null, parser);
        }
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
     * Pooled path: take (or create) the kept
     * {@link io.vidocq.champollion.jsonp.internal.ChampollionJsonParser},
     * {@code reset(reader)}, read the value, and put it back for the next
     * call. The provided {@code reader} is not closed — that is the
     * caller's responsibility (consistent with the usual
     * {@code fromJson(Reader, ...)} contract).
     */
    private Object readValuePooled(Reader reader, Type runtimeType) {
        var parser = parserSlot.getAndSet(null);
        if (parser == null) {
            parser = new io.vidocq.champollion.jsonp.internal.ChampollionJsonParser(reader).keepingKeys();
        } else {
            parser.reset(reader);
        }
        try {
            return readValue(parser, runtimeType);
        } finally {
            parserSlot.compareAndSet(null, parser);
        }
    }

    @Override public <T> T fromJson(InputStream stream, Class<T> type) {
        return fromJson(stream, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(InputStream stream, Type runtimeType) {
        if (jsonProvider instanceof io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider) {
            return (T) readValueFromStream(stream, runtimeType);
        }
        try (JsonParser p = jsonProvider.createParser(stream)) {
            return (T) readValue(p, runtimeType);
        }
    }

    /**
     * Reads a body of at most {@link #READ_BUFFER_SIZE} bytes whole, and parses it as a string when it is
     * UTF-8, with or without a byte order mark: {@code new String} replaces malformed input as the decoder of
     * the streaming path does. Any other encoding (RFC 8259 §8.1, told by the zero bytes of the head), and a
     * longer body, take the streaming path with the bytes already read put back in front. The stream is
     * closed in every case, as the streaming parser closes it.
     */
    private Object readValueFromStream(InputStream stream, Type runtimeType) {
        byte[] buffer = readBufferSlot.getAndSet(null);
        if (buffer == null) buffer = new byte[READ_BUFFER_SIZE];
        String text;
        byte[] otherEncoding = null;
        try (InputStream in = stream) {
            int length = readUpTo(in, buffer);
            int next = length == buffer.length ? in.read() : -1;
            if (next >= 0) {
                // Longer than the buffer: stream it all, the bytes already read first.
                byte[] head = java.util.Arrays.copyOf(buffer, length + 1);
                head[length] = (byte) next;
                return readStreamed(new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head), in), runtimeType);
            }
            text = utf8Text(buffer, length);
            if (text == null) otherEncoding = java.util.Arrays.copyOf(buffer, length);
        } catch (java.io.IOException e) {
            throw new JsonbException("I/O error while reading JSON", e);
        } finally {
            readBufferSlot.setRelease(buffer);
        }
        return otherEncoding != null
                ? readStreamed(new java.io.ByteArrayInputStream(otherEncoding), runtimeType)
                : readValuePooledFromString(text, runtimeType);
    }

    /** Reads with a parser of the provider, which detects the encoding and closes {@code in}. */
    private Object readStreamed(InputStream in, Type runtimeType) {
        try (JsonParser p = jsonProvider.createParser(in)) {
            return readValue(p, runtimeType);
        }
    }

    /** Reads {@code in} into {@code buffer} until the end of the stream or a full buffer; returns how many bytes. */
    private static int readUpTo(InputStream in, byte[] buffer) throws java.io.IOException {
        int length = 0;
        while (length < buffer.length) {
            int n = in.read(buffer, length, buffer.length - length);
            if (n < 0) break;
            length += n;
        }
        return length;
    }

    /**
     * The text of {@code bytes[0, length)} when it is UTF-8: no zero among the first two bytes (a zero there
     * means UTF-16 or UTF-32, RFC 8259 §8.1) and no UTF-16 byte order mark. A UTF-8 byte order mark is skipped.
     * {@code null} otherwise, and for an empty body, which the streaming path reports.
     */
    private static String utf8Text(byte[] bytes, int length) {
        if (length == 0) return null;
        if (length >= 2) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            if (b0 == 0 || b1 == 0 || (b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) return null;
        } else if (bytes[0] == 0) {
            return null;
        }
        int start = length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF ? 3 : 0;
        return new String(bytes, start, length - start, java.nio.charset.StandardCharsets.UTF_8);
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

    /** Releases the parser this instance keeps between two reads. */
    @Override public void close() {
        parserSlot.set(null);
    }
}
