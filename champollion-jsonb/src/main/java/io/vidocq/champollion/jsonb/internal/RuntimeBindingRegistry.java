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
import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonGenerator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registry of {@link BindingWriter}s resolved the first time a type is seen.
 *
 * <p>Cache: {@link ClassValue} to amortize reflection cost. For parameterized
 * types (generic collections, M4.3), a {@code ConcurrentHashMap<Type, ...>}
 * will be added alongside it.</p>
 */
final class RuntimeBindingRegistry {

    /** Suffix of the class that carries the annotations of a package. */
    private static final String PACKAGE_INFO = ".package-info";
    /** The value of a JSON-B format annotation that asks for the default format. */
    private static final String DEFAULT_FORMAT = "##default";

    private final String defaultDateFormat;
    private final boolean writeNullValues;
    private final String binaryDataStrategy;
    private final String propertyNamingStrategy;
    private final String propertyOrderStrategy;
    private final jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy;
    private final java.util.Locale configLocale;
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> globalAdapters = new java.util.LinkedHashMap<>();
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> globalSerializers = new java.util.LinkedHashMap<>();
    private final ChampollionSerializationContext serContext = new ChampollionSerializationContext(this);

    /** The adapters registered with {@code JsonbConfig.withAdapters}, by their {@code Original} type. */
    @SuppressWarnings("rawtypes")
    void setGlobalAdapters(java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> map) {
        this.globalAdapters.clear();
        if (map != null) this.globalAdapters.putAll(map);
    }

    @SuppressWarnings("rawtypes")
    void setGlobalSerializers(java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> map) {
        this.globalSerializers.clear();
        if (map != null) this.globalSerializers.putAll(map);
    }

    /**
     * The generated bindings of the application. A type that has one is written by it wherever it appears: as the
     * root, as an element of a collection, as a map value or as a member of another object. Set before the first
     * write, since the per-class writers are cached. Vidocq/vidocq#382.
     */
    private StaticBindings staticBindings = StaticBindings.EMPTY;

    void setStaticBindings(StaticBindings bindings) {
        this.staticBindings = bindings == null ? StaticBindings.EMPTY : bindings;
    }

    RuntimeBindingRegistry() {
        this(null, false, null, null, null, null, null);
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues) {
        this(defaultDateFormat, writeNullValues, null, null, null, null, null);
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy) {
        this(defaultDateFormat, writeNullValues, binaryDataStrategy, null, null, null, null);
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy,
                           String propertyNamingStrategy, String propertyOrderStrategy,
                           jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy) {
        this(defaultDateFormat, writeNullValues, binaryDataStrategy, propertyNamingStrategy, propertyOrderStrategy, propertyVisibilityStrategy, null);
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy,
                           String propertyNamingStrategy, String propertyOrderStrategy,
                           jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                           java.util.Locale configLocale) {
        this.defaultDateFormat = defaultDateFormat;
        this.writeNullValues = writeNullValues;
        this.binaryDataStrategy = binaryDataStrategy;
        this.propertyNamingStrategy = propertyNamingStrategy;
        this.propertyOrderStrategy = propertyOrderStrategy;
        this.propertyVisibilityStrategy = propertyVisibilityStrategy;
        this.configLocale = configLocale;
    }

    private final ClassValue<BindingWriter> cache = new ComputedClassValue<>(this::resolveClass);

    /** The writer of the raw class {@code type}, computed once per class by {@link #cache}. */
    private BindingWriter resolveClass(Class<?> type) {
        // Static-first, as at the root: a generated binding replaces the introspective writer of its type.
        @SuppressWarnings("rawtypes")
        JsonbBinding staticBinding = staticBindings.get(type);
        if (staticBinding != null) {
            return staticBinding::write;
        }
        BindingWriter built = Builtins.lookup(type);
        if (built != null) return built;
        if (type.isEnum()) return Builtins.ENUM;
        if (type == Number.class) return dynamicWriter();   // abstract → resolve by runtime value
        if (Modifier.isAbstract(type.getModifiers()) && !type.isInterface()
                && !java.util.Map.class.isAssignableFrom(type)
                && !java.util.Collection.class.isAssignableFrom(type)) {
            return dynamicWriter();
        }
        // M4.5: polymorphism — if @JsonbTypeInfo is present (on the class or a
        // supertype), emit a discriminant member before the concrete type members.
        var info = findTypeInfo(type);
        if (info != null) return polymorphicWriter(info);
        if (type.isRecord()) return resolveRecord(type);
        if (type.isPrimitive()) {
            // Boxed by the caller; we should not reach this path except in odd cases.
            return cache.get(boxOf(type));
        }
        if (java.util.Map.class.isAssignableFrom(type)) {
            return mapWriter(dynamicWriter());
        }
        if (java.util.Collection.class.isAssignableFrom(type)) {
            return collectionWriter(dynamicWriter());
        }
        if (type == java.util.Optional.class) {
            return optionalWriter(dynamicWriter());
        }
        return resolvePojo(type);
    }

    BindingWriter writerFor(Type t) {
        if (t == null) return dynamicWriter();
        // Registered global adapter or serializer for this type: short-circuit.
        Class<?> raw = rawOf(t);
        var adapterWriter = adapterWriterFor(raw);
        if (adapterWriter != null) return adapterWriter;
        var serializerWriter = serializerWriterFor(raw);
        if (serializerWriter != null) return serializerWriter;
        if (t instanceof Class<?> c) {
            if (c.isArray()) return arrayWriter(c.getComponentType());
            if (c == Object.class) return dynamicWriter();
            if (c.isInterface() && !java.util.Map.class.isAssignableFrom(c)
                    && !java.util.Collection.class.isAssignableFrom(c)) {
                // Generic interface (e.g. TypeContainer<T>): defer to runtime via the concrete class.
                return dynamicWriter();
            }
            return cache.get(c);
        }
        if (t instanceof java.lang.reflect.ParameterizedType p) return parameterizedWriter(p);
        if (t instanceof java.lang.reflect.GenericArrayType ga) {
            return arrayWriter(rawOf(ga.getGenericComponentType()));
        }
        if (t instanceof java.lang.reflect.TypeVariable<?> || t instanceof java.lang.reflect.WildcardType) {
            return dynamicWriter();
        }
        return cache.get(rawOf(t));
    }

    /**
     * Looks up a global adapter registered via {@code JsonbConfig.withAdapters}
     * whose {@code Original} type is assignable from {@code raw}. Returns a writer
     * that invokes {@code adaptToJson} and then delegates to the writer for the
     * {@code Adapted} type.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BindingWriter adapterWriterFor(Class<?> raw) {
        if (globalAdapters.isEmpty() || raw == null || raw == Object.class) return null;
        final jakarta.json.bind.adapter.JsonbAdapter adapter = registeredFor(globalAdapters, raw);
        if (adapter == null) return null;
        java.lang.reflect.Type adaptedGeneric = findAdaptedGenericType(adapter.getClass());
        BindingWriter inner = writerForGeneric(adaptedGeneric);
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            Object adapted;
            try { adapted = adapter.adaptToJson(value); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on toJson: " + ex.getMessage(), ex); }
            if (adapted == null) g.writeNull();
            else inner.write(g, adapted);
        };
    }

    /** Variant of {@link #writerFor(Type)} without the adapter short-circuit, to avoid infinite recursion. */
    private BindingWriter writerForRaw(Class<?> c) {
        if (c == null || c == Object.class) return dynamicWriter();
        if (c.isArray()) return arrayWriter(c.getComponentType());
        return cache.get(c);
    }

    /**
     * Looks up a registered global {@code JsonbSerializer} whose {@code T} type
     * is assignable from {@code raw}. Returns a writer that invokes
     * {@code serialize(value, gen, ctx)}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BindingWriter serializerWriterFor(Class<?> raw) {
        if (globalSerializers.isEmpty() || raw == null || raw == Object.class) return null;
        final jakarta.json.bind.serializer.JsonbSerializer ser = registeredFor(globalSerializers, raw);
        if (ser == null) return null;
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            ser.serialize(value, g, serContext);
        };
    }

    /** The value registered for {@code raw}: its exact type first, then the first assignable one. */
    private static <V> V registeredFor(java.util.Map<Class<?>, V> registry, Class<?> raw) {
        V direct = registry.get(raw);
        if (direct != null) return direct;
        for (var entry : registry.entrySet()) {
            if (entry.getKey().isAssignableFrom(raw)) return entry.getValue();
        }
        return null;
    }

    /** Writer for {@code @JsonbTypeSerializer} on a record component / Method / Field. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    java.util.Optional<BindingWriter> customSerializerWriter(java.lang.reflect.AnnotatedElement member,
                                                             java.lang.reflect.Field underlying) {
        var ann = member == null ? null : member.getAnnotation(jakarta.json.bind.annotation.JsonbTypeSerializer.class);
        if (ann == null && underlying != null) {
            ann = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbTypeSerializer.class);
        }
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.serializer.JsonbSerializer> serClass = ann.value();
        jakarta.json.bind.serializer.JsonbSerializer ser;
        try {
            // §5 — CDI resolution if a container is available, otherwise newInstance.
            ser = CdiResolver.resolve(serClass);
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbSerializer " + serClass, e);
        }
        return java.util.Optional.of((g, value) -> {
            if (value == null) { g.writeNull(); return; }
            ser.serialize(value, g, serContext);
        });
    }

    /** Writer that resolves at runtime via {@code value.getClass()}: needed for
     *  untyped collections/maps (raw types) or when the static type is {@code Object}. */
    private BindingWriter dynamicWriter() {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            cache.get(value.getClass()).write(g, value);
        };
    }

    private static Class<?> rawOf(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    /**
     * Resolution for parameterized types: Collection&lt;E&gt;, Map&lt;String,V&gt;,
     * Optional&lt;E&gt;. The resulting writer is <em>type-specific</em> and not cached
     * by class — the JVM still reuses the same instance for identical types via
     * internal memoization (M5: dedicated cache if needed).
     */
    private BindingWriter parameterizedWriter(java.lang.reflect.ParameterizedType p) {
        Class<?> raw = (Class<?>) p.getRawType();
        Type[] args = p.getActualTypeArguments();
        if (java.util.Map.class.isAssignableFrom(raw)) {
            BindingWriter valueWriter = args.length >= 2 ? writerFor(args[1]) : dynamicWriter();
            return mapWriter(valueWriter);
        }
        if (java.util.Collection.class.isAssignableFrom(raw)) {
            BindingWriter elemWriter = args.length >= 1 ? writerFor(args[0]) : dynamicWriter();
            return collectionWriter(elemWriter);
        }
        if (raw == java.util.Optional.class) {
            BindingWriter inner = args.length >= 1 ? writerFor(args[0]) : dynamicWriter();
            return optionalWriter(inner);
        }
        return cache.get(raw);
    }

    private static final BindingWriter INT_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (int x : (int[]) v) g.write(x);
        g.writeEnd();
    };
    private static final BindingWriter LONG_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (long x : (long[]) v) g.write(x);
        g.writeEnd();
    };
    private static final BindingWriter DOUBLE_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (double x : (double[]) v) g.write(x);
        g.writeEnd();
    };
    private static final BindingWriter FLOAT_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (float x : (float[]) v) g.write(x);
        g.writeEnd();
    };
    private static final BindingWriter SHORT_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (short x : (short[]) v) g.write(x);
        g.writeEnd();
    };
    private static final BindingWriter CHAR_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (char x : (char[]) v) g.write(String.valueOf(x));
        g.writeEnd();
    };
    private static final BindingWriter BOOLEAN_ARRAY = (g, v) -> {
        g.writeStartArray();
        for (boolean x : (boolean[]) v) g.write(x);
        g.writeEnd();
    };

    private BindingWriter arrayWriter(Class<?> componentType) {
        if (componentType == byte.class) return byteArrayWriter();
        if (componentType == int.class) return INT_ARRAY;
        if (componentType == long.class) return LONG_ARRAY;
        if (componentType == double.class) return DOUBLE_ARRAY;
        if (componentType == float.class) return FLOAT_ARRAY;
        if (componentType == short.class) return SHORT_ARRAY;
        if (componentType == char.class) return CHAR_ARRAY;
        if (componentType == boolean.class) return BOOLEAN_ARRAY;
        BindingWriter elem = writerFor(componentType);
        return (g, v) -> {
            g.writeStartArray();
            for (Object x : (Object[]) v) {
                if (x == null) g.writeNull();
                else elem.write(g, x);
            }
            g.writeEnd();
        };
    }

    /**
     * JSON-B 3.0 §3.3.1 — byte[]:
     * <ul>
     *   <li>BYTE (default): array of signed integers [-128..127]</li>
     *   <li>BASE_64: standard base64 string with padding</li>
     *   <li>BASE_64_URL: URL-safe base64 string without padding</li>
     * </ul>
     */
    private BindingWriter byteArrayWriter() {
        String s = binaryDataStrategy;
        if ("BASE_64".equals(s)) {
            return (g, v) -> g.write(java.util.Base64.getEncoder().encodeToString((byte[]) v));
        }
        if ("BASE_64_URL".equals(s)) {
            return (g, v) -> g.write(java.util.Base64.getUrlEncoder().encodeToString((byte[]) v));
        }
        // BYTE by default
        return (g, v) -> {
            g.writeStartArray();
            for (byte b : (byte[]) v) g.write(b);
            g.writeEnd();
        };
    }

    private BindingWriter collectionWriter(BindingWriter elem) {
        return (g, v) -> {
            g.writeStartArray();
            for (Object x : (Iterable<?>) v) {
                if (x == null) g.writeNull();
                else elem.write(g, x);
            }
            g.writeEnd();
        };
    }

    private BindingWriter mapWriter(BindingWriter valueWriter) {
        return (g, v) -> {
            g.writeStartObject();
            for (var entry : ((java.util.Map<?, ?>) v).entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String s)) {
                    throw new JsonbException("Map keys must be String for JSON-B (got " + (key == null ? "null" : key.getClass()) + ")");
                }
                Object val = entry.getValue();
                g.writeKey(s);
                if (val == null) g.writeNull();
                else valueWriter.write(g, val);
            }
            g.writeEnd();
        };
    }

    private BindingWriter optionalWriter(BindingWriter inner) {
        return (g, v) -> {
            var opt = (java.util.Optional<?>) v;
            if (opt.isEmpty()) g.writeNull();
            else inner.write(g, opt.get());
        };
    }

    // ===== Resolution =====

    private static Class<?> boxOf(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        if (p == boolean.class) return Boolean.class;
        return Object.class;
    }

    private BindingWriter resolveRecord(Class<?> type) {
        RecordComponent[] comps = type.getRecordComponents();
        var props = new ArrayList<Property>(comps.length);
        for (RecordComponent c : comps) {
            if (isJsonbTransient(c)) continue;
            Method accessor = c.getAccessor();
            String name = jsonbName(c, c.getName());
            boolean nillable = isJsonbNillable(c) || writeNullValues;
            // M4.4f: @JsonbTypeAdapter → apply the adapter before writing.
            // M4.4d: @JsonbDateFormat on component > M4.7 global JSONB_DATE_FORMAT > runtime ISO.
            BindingWriter w = customAdapterWriter(c)
                    .or(() -> customDateWriter(c))
                    .or(() -> globalDateWriter(c.getType()))
                    .orElseGet(() -> writerFor(c.getGenericType()));
            // P6.1 — prefer LambdaMetafactory; fallback to reflection setAccessible.
            Accessor acc = tryLambdaAccessor(type, accessor.getName(), c.getType());
            if (acc == null) {
                accessor.trySetAccessible();
                acc = new MethodAccessor(accessor);
            }
            props.add(new Property(name, acc, w, nillable));
        }
        return (g, value) -> writeObject(g, value, props);
    }

    /**
    /**
     * If the component has {@code @JsonbTypeAdapter(class)}, returns a writer that:
     * 1. instantiates the adapter via its no-arg ctor,
     * 2. invokes {@code adaptToJson(value)} on the value,
     * 3. delegates writing the result to the writer for the {@code Adapted} type
     *    (derived from the parameterized super interface
     *    {@code JsonbAdapter<Original, Adapted>}).
     */
    private java.util.Optional<BindingWriter> customAdapterWriter(RecordComponent c) {
        var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        if (ann == null) {
            ann = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        }
        return adapterWriterFromAnnotation(ann);
    }

    /**
     * Searches for {@code @JsonbTypeAdapter} on {@code member} (field or method)
     * or, as a fallback, on the {@code underlying} field. Returns the adapted
     * writer or empty.
     */
    java.util.Optional<BindingWriter> customAdapterWriter(java.lang.reflect.AnnotatedElement member,
                                                          java.lang.reflect.Field underlying) {
        var ann = member == null ? null : member.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        if (ann == null && underlying != null) {
            ann = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        }
        return adapterWriterFromAnnotation(ann);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private java.util.Optional<BindingWriter> adapterWriterFromAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter ann) {
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass = ann.value();
        jakarta.json.bind.adapter.JsonbAdapter adapter;
        try {
            // §5 — CDI resolution if a container is available, otherwise newInstance.
            adapter = CdiResolver.resolve(adapterClass);
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbAdapter " + adapterClass, e);
        }
        java.lang.reflect.Type adaptedGeneric = findAdaptedGenericType(adapterClass);
        BindingWriter inner = writerForGeneric(adaptedGeneric);
        return java.util.Optional.of((g, value) -> {
            Object adapted;
            try { adapted = adapter.adaptToJson(value); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on toJson: " + ex.getMessage(), ex); }
            if (adapted == null) g.writeNull();
            else inner.write(g, adapted);
        });
    }

    /** Variant of {@link #writerFor(Type)} without the adapter short-circuit. Preserves ParameterizedType. */
    private BindingWriter writerForGeneric(java.lang.reflect.Type t) {
        if (t == null) return dynamicWriter();
        if (t instanceof java.lang.reflect.ParameterizedType p) return parameterizedWriter(p);
        if (t instanceof java.lang.reflect.GenericArrayType ga) return arrayWriter(rawOf(ga.getGenericComponentType()));
        Class<?> raw = rawOf(t);
        return writerForRaw(raw);
    }

    /** Examines the generics of the {@code JsonbAdapter<Original, Adapted>} interface. */
    @SuppressWarnings("rawtypes")
    static Class<?> findAdaptedType(Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass) {
        java.lang.reflect.Type t = findAdaptedGenericType(adapterClass);
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
        return Object.class;
    }

    /** Returns the full {@link java.lang.reflect.Type} (including generics) of the Adapted parameter. */
    @SuppressWarnings("rawtypes")
    static java.lang.reflect.Type findAdaptedGenericType(Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass) {
        for (Class<?> c = adapterClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) {
                if (t instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == jakarta.json.bind.adapter.JsonbAdapter.class) {
                    return pt.getActualTypeArguments()[1];
                }
            }
        }
        return Object.class;
    }

    /**
     * Date writer for a given member (field or method), searching @JsonbDateFormat at
     * multiple levels: member > declaring type > package > JsonbConfig.DATE_FORMAT.
     * Returns empty if {@code rawType} is not a supported date type or if no pattern exists.
     */
    private java.util.Optional<BindingWriter> dateWriter(Class<?> rawType, java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        if (!isDateLikeType(rawType)) return java.util.Optional.empty();
        DateFormatSpec spec = findDateFormatSpec(member, declaringType);
        if (spec == null) return java.util.Optional.empty();
        return java.util.Optional.of(makeDateWriter(rawType, spec));
    }

    private java.util.Optional<BindingWriter> globalDateWriter(Class<?> rawType) {
        if (!isDateLikeType(rawType)) return java.util.Optional.empty();
        if (defaultDateFormat == null) return java.util.Optional.empty();
        java.util.Locale loc = configLocale != null ? configLocale : java.util.Locale.getDefault();
        return java.util.Optional.of(makeDateWriter(rawType, new DateFormatSpec(defaultDateFormat, loc)));
    }

    /**
     * Custom numeric writer via @JsonbNumberFormat (member > type > package > config).
     * Returns empty if not applicable.
     */
    private java.util.Optional<BindingWriter> numberWriter(Class<?> rawType, java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        if (!isNumericType(rawType)) return java.util.Optional.empty();
        var spec = findNumberFormatSpec(member, declaringType);
        if (spec == null) return java.util.Optional.empty();
        java.text.NumberFormat fmt;
        if (DEFAULT_FORMAT.equals(spec.pattern()) || spec.pattern().isEmpty()) {
            var sym = java.text.DecimalFormatSymbols.getInstance(spec.locale());
            normalizeFrenchGroupSeparator(sym);
            fmt = new java.text.DecimalFormat(((java.text.DecimalFormat) java.text.NumberFormat.getInstance(spec.locale())).toPattern(), sym);
        } else {
            var sym = new java.text.DecimalFormatSymbols(spec.locale());
            normalizeFrenchGroupSeparator(sym);
            fmt = new java.text.DecimalFormat(spec.pattern(), sym);
        }
        return java.util.Optional.of((g, value) -> {
            if (value == null) g.writeNull();
            else g.write(fmt.format(value));
        });
    }

    /**
     * TCK compatibility for §JsonbNumberFormat: since Java 13 / modern CLDR, the
     * French grouping separator is U+202F (NNBSP, NARROW NO-BREAK SPACE); the
     * JSON-B 3.0 TCK expects U+00A0 (NBSP). Reimpose NBSP to stay compliant.
     */
    private static void normalizeFrenchGroupSeparator(java.text.DecimalFormatSymbols sym) {
        char sep = sym.getGroupingSeparator();
        if (sep == '\u202F' || sep == ' ') {
            sym.setGroupingSeparator('\u00A0');
        }
    }

    static boolean isNumericType(Class<?> rawType) {
        if (rawType.isPrimitive()) {
            return rawType == int.class || rawType == long.class || rawType == double.class
                    || rawType == float.class || rawType == short.class || rawType == byte.class;
        }
        return Number.class.isAssignableFrom(rawType);
    }

    record NumberFormatSpec(String pattern, java.util.Locale locale) {}

    private NumberFormatSpec findNumberFormatSpec(java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        var ann = formatAnnotation(member, declaringType, jakarta.json.bind.annotation.JsonbNumberFormat.class);
        return ann == null ? null : new NumberFormatSpec(ann.value(), parseLocale(ann.locale()));
    }

    /**
     * The format annotation that applies to a member: on the member itself, else on its declaring type or a
     * superclass, else on the package of one of them.
     */
    private static <A extends java.lang.annotation.Annotation> A formatAnnotation(
            java.lang.reflect.AnnotatedElement member, Class<?> declaringType, Class<A> annotationType) {
        A ann = member == null ? null : member.getAnnotation(annotationType);
        if (ann == null) ann = typeAnnotation(declaringType, annotationType);
        if (ann == null) ann = packageAnnotation(declaringType, annotationType);
        return ann;
    }

    /** The annotation on {@code type} or its closest superclass carrying it, or {@code null}. */
    private static <A extends java.lang.annotation.Annotation> A typeAnnotation(Class<?> type, Class<A> annotationType) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            A ann = c.getAnnotation(annotationType);
            if (ann != null) return ann;
        }
        return null;
    }

    /**
     * The annotation on the package of {@code type} or of its closest superclass carrying it, or {@code null}.
     * Superclass packages count because anonymous inner classes inherit from their parent.
     */
    private static <A extends java.lang.annotation.Annotation> A packageAnnotation(Class<?> type, Class<A> annotationType) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var pkg = c.getPackage();
            A ann = pkg == null ? null : loadedPackage(c, pkg).getAnnotation(annotationType);
            if (ann != null) return ann;
        }
        return null;
    }

    /** Loads the package-info class of {@code pkg} so that its annotations are visible, and returns {@code pkg}. */
    private static Package loadedPackage(Class<?> member, Package pkg) {
        ClassLoader cl = member.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try {
            Class.forName(pkg.getName() + PACKAGE_INFO, false, cl);
        } catch (ClassNotFoundException | LinkageError _) {
            // No loadable package-info: the package simply carries no annotation.
        }
        return pkg;
    }

    static boolean isDateLikeType(Class<?> rawType) {
        return rawType == java.time.LocalDate.class
                || rawType == java.time.LocalDateTime.class
                || rawType == java.time.OffsetDateTime.class
                || rawType == java.time.ZonedDateTime.class
                || rawType == java.time.Instant.class
                || rawType == java.time.LocalTime.class
                || rawType == java.time.OffsetTime.class
                || rawType == java.time.Duration.class
                || rawType == java.time.Period.class
                || java.util.Date.class.isAssignableFrom(rawType)
                || java.util.Calendar.class.isAssignableFrom(rawType);
    }

    record DateFormatSpec(String pattern, java.util.Locale locale) {
        boolean isDefault() { return DEFAULT_FORMAT.equals(pattern); }
    }

    static DateFormatSpec findDateFormatSpec(java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        var ann = formatAnnotation(member, declaringType, jakarta.json.bind.annotation.JsonbDateFormat.class);
        return ann == null ? null : new DateFormatSpec(ann.value(), parseLocale(ann.locale()));
    }

    private static java.util.Locale parseLocale(String tag) {
        // §4.7.3: "##default" on @JsonbNumberFormat / @JsonbDateFormat means the
        // canonical JSON-B format (group separator ',' decimal '.'), equivalent to Locale.ROOT.
        if (tag == null || tag.isEmpty() || DEFAULT_FORMAT.equals(tag)) return java.util.Locale.ROOT;
        return java.util.Locale.forLanguageTag(tag);
    }

    /**
     * Detects whether a pattern uses characters specific to {@link java.time.format.DateTimeFormatter}
     * that are not supported by {@link java.text.SimpleDateFormat} (notably {@code x}, {@code X}, {@code Z}
     * with certain counts). Date/Calendar are then routed through DateTimeFormatter after conversion
     * to {@code ZonedDateTime}.
     */
    private static boolean patternUsesDateTimeFormatterChars(String pattern) {
        if (pattern == null) return false;
        boolean inLiteral = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && (c == 'x' || c == 'O' || c == 'V')) {
                return true;
            }
        }
        return false;
    }

    private BindingWriter makeDateWriter(Class<?> rawType, DateFormatSpec spec) {
        if (java.util.Date.class.isAssignableFrom(rawType)) return utilDateWriter(spec);
        if (java.util.Calendar.class.isAssignableFrom(rawType)) return calendarWriter(spec);
        // Duration / Period: fixed ISO 8601 format — DateTimeFormatter patterns do not apply.
        if (rawType == java.time.Duration.class || rawType == java.time.Period.class) {
            return (g, value) -> {
                if (value == null) { g.writeNull(); return; }
                g.write(value.toString());
            };
        }
        return temporalWriter(spec);
    }

    private static BindingWriter utilDateWriter(DateFormatSpec spec) {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            if (spec.isDefault()) {
                g.write(((java.util.Date) value).toInstant().atZone(java.time.ZoneId.of("UTC"))
                        .format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME));
            } else if (patternUsesDateTimeFormatterChars(spec.pattern())) {
                var zdt = ((java.util.Date) value).toInstant().atZone(java.time.ZoneOffset.UTC);
                var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
                g.write(fmt.format(zdt));
            } else {
                var sdf = new java.text.SimpleDateFormat(spec.pattern(), spec.locale());
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                g.write(sdf.format((java.util.Date) value));
            }
        };
    }

    private static BindingWriter calendarWriter(DateFormatSpec spec) {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            var cal = (java.util.Calendar) value;
            var zdt = cal.toInstant().atZone(cal.getTimeZone().toZoneId());
            if (spec.isDefault()) {
                g.write(zdt.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME));
            } else {
                // Convert via ZonedDateTime to align with the ZonedDateTime UTC format
                // (strict IJSON §3.5.1 requires Date/Calendar to produce the same
                // format as a ZonedDateTime).
                var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
                g.write(fmt.format(zdt));
            }
        };
    }

    /**
     * java.time: convert to ZonedDateTime UTC when needed to absorb patterns
     * that require absolute fields (yyyy/MM/dd HH:mm:ss).
     */
    private static BindingWriter temporalWriter(DateFormatSpec spec) {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            if (spec.isDefault()) {
                g.write(value.toString());
                return;
            }
            var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
            g.write(fmt.format(formattableTemporal(value)));
        };
    }

    private static java.time.temporal.TemporalAccessor formattableTemporal(Object value) {
        return switch (value) {
            case java.time.LocalDate ld -> ld.atStartOfDay(java.time.ZoneOffset.UTC);
            case java.time.LocalDateTime ldt -> ldt.atZone(java.time.ZoneOffset.UTC);
            case java.time.LocalTime lt -> lt.atDate(java.time.LocalDate.of(1970, 1, 1)).atZone(java.time.ZoneOffset.UTC);
            case java.time.Instant inst -> inst.atZone(java.time.ZoneOffset.UTC);
            case java.time.temporal.TemporalAccessor ta -> ta;
            default -> throw new JsonbException("Cannot format type with date pattern: " + value.getClass());
        };
    }

    /**
     * If the component has {@code @JsonbDateFormat}, returns a writer that formats
     * the value via {@link java.time.format.DateTimeFormatter#ofPattern}. Otherwise empty.
     */
    private static java.util.Optional<BindingWriter> customDateWriter(RecordComponent c) {
        var direct = c.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
        var fromAccessor = direct == null
                ? c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class)
                : null;
        var ann = direct != null ? direct : fromAccessor;
        if (ann == null) return java.util.Optional.empty();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(ann.value());
        Class<?> rawType = c.getType();
        return java.util.Optional.of((g, value) -> {
            // Delegates to TemporalAccessor.format when applicable.
            if (value == null) { g.writeNull(); return; }
            if (value instanceof java.time.temporal.TemporalAccessor t) {
                g.write(fmt.format(t));
            } else {
                throw new JsonbException("@JsonbDateFormat applied to non-temporal type: " + rawType);
            }
        });
    }

    private BindingWriter resolvePojo(Class<?> type) {
        validateTransientCombinations(type);
        // 1) Getters of the hierarchy; 2) public fields not covered by a getter and not hidden.
        var hiddenProps = new java.util.HashSet<String>();
        var getterByProp = discoverGetters(type, hiddenProps);
        var props = new ArrayList<Property>();
        for (var e : getterByProp.entrySet()) {
            props.add(getterProperty(type, e.getKey(), e.getValue()));
        }
        for (Field f : type.getFields()) {
            if (isFieldCandidate(f, getterByProp.keySet(), hiddenProps)) props.add(fieldProperty(type, f));
        }

        applyVisibilityStrategy(type, getterByProp.keySet(), hiddenProps, props);
        rejectDuplicateNames(type, props);

        // Tri selon @JsonbPropertyOrder + JsonbConfig.PROPERTY_ORDER_STRATEGY.
        applyPropertyOrder(type, props);

        if (props.isEmpty()) {
            // JSON-B 3.0 §3.7: object with no visible property → empty JSON object {}.
            return (g, value) -> {
                if (value == null) g.writeNull();
                else { g.writeStartObject(); g.writeEnd(); }
            };
        }
        return (g, value) -> writeObject(g, value, props);
    }

    /**
     * Discovers the getters of the hierarchy (including private/protected/package ones), by property name.
     * JSON-B 3.0 §3.7.1: only public getters are considered by default, but the EXISTENCE of a non-public
     * accessor hides the property (a public field is not enough); the names of the hidden properties are added
     * to {@code hiddenProps} and removed from the result.
     */
    private static java.util.LinkedHashMap<String, Method> discoverGetters(Class<?> type, java.util.Set<String> hiddenProps) {
        var getterByProp = new java.util.LinkedHashMap<String, Method>();
        for (Method m : accessorCandidates(type)) {
            String propName = getterPropertyName(m);
            if (propName == null) continue;
            if (hidesProperty(type, m, propName)) hiddenProps.add(propName);
            else preferGetter(getterByProp, propName, m);
        }
        // Removes hidden props in case both public/non-public are present
        getterByProp.keySet().removeAll(hiddenProps);
        return getterByProp;
    }

    /**
     * The methods that may be accessors of {@code type}: those declared along its superclasses, then the default
     * methods of its interfaces, each signature taken from its most specific declaration only. An override decides
     * the visibility of the method it overrides: a public override of a protected getter is a public getter.
     */
    static List<Method> accessorCandidates(Class<?> type) {
        List<Method> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Class<?>> interfaces = new java.util.ArrayDeque<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            addMostSpecific(c.getDeclaredMethods(), seen, out, false);
            interfaces.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        java.util.Set<Class<?>> visited = new java.util.HashSet<>();
        while (!interfaces.isEmpty()) {
            Class<?> itf = interfaces.poll();
            if (!visited.add(itf)) continue;
            addMostSpecific(itf.getDeclaredMethods(), seen, out, true);
            interfaces.addAll(java.util.Arrays.asList(itf.getInterfaces()));
        }
        return out;
    }

    private static void addMostSpecific(Method[] declared, java.util.Set<String> seen, List<Method> out,
                                        boolean defaultsOnly) {
        for (Method m : declared) {
            if (m.isBridge() || m.isSynthetic() || Modifier.isStatic(m.getModifiers())) continue;
            if (defaultsOnly && !m.isDefault()) continue;
            String signature = m.getName() + java.util.Arrays.toString(m.getParameterTypes());
            if (seen.add(signature)) out.add(m);
        }
    }

    /** The bean property read by {@code m}, or {@code null} when {@code m} is not an instance getter. */
    private static String getterPropertyName(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()
                || m.getParameterCount() != 0 || m.getReturnType() == void.class) {
            return null;
        }
        return beanPropertyOf(m);
    }

    /**
     * True when the getter hides its property: it is {@code @JsonbTransient} or not public (even if a public
     * field exists), or (§3.7.1) its underlying field is static, transient or {@code @JsonbTransient}.
     */
    private static boolean hidesProperty(Class<?> type, Method m, String propName) {
        if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)
                || !Modifier.isPublic(m.getModifiers())) {
            return true;
        }
        Field underlying = findFieldByName(type, propName);
        if (underlying == null) return false;
        int fMods = underlying.getModifiers();
        return Modifier.isStatic(fMods) || Modifier.isTransient(fMods)
                || underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    /** Keeps the preferred getter of a property: a {@code getXxx} over an {@code isXxx}, else the most specific type. */
    private static void preferGetter(java.util.Map<String, Method> getterByProp, String propName, Method m) {
        Method existing = getterByProp.putIfAbsent(propName, m);
        if (existing == null) return;
        boolean newIsGet = m.getName().startsWith("get");
        boolean existingIsGet = existing.getName().startsWith("get");
        boolean moreSpecific = existing.getReturnType().isAssignableFrom(m.getReturnType())
                && existing.getReturnType() != m.getReturnType();
        if ((newIsGet && !existingIsGet) || moreSpecific) getterByProp.put(propName, m);
    }

    private Property getterProperty(Class<?> type, String propName, Method m) {
        m.trySetAccessible();
        String name = jsonbNameFromMethod(m, propName);
        Field underlying = findFieldByName(type, propName);
        boolean nillable = computeNillable(m, underlying, type);
        // Date / Number format: @JsonbDateFormat / @JsonbNumberFormat on method, underlying field, type, package, or global config.
        var memberForDate = annotatedMember(m, underlying, jakarta.json.bind.annotation.JsonbDateFormat.class);
        var memberForNumber = annotatedMember(m, underlying, jakarta.json.bind.annotation.JsonbNumberFormat.class);
        // §4.7 — @JsonbTypeAdapter / @JsonbTypeSerializer on the getter or its underlying field.
        BindingWriter w = customAdapterWriter(m, underlying)
                .or(() -> customSerializerWriter(m, underlying))
                .or(() -> dateWriter(m.getReturnType(), memberForDate, type))
                .or(() -> globalDateWriter(m.getReturnType()))
                .or(() -> numberWriter(m.getReturnType(), memberForNumber, type))
                .orElseGet(() -> writerFor(m.getGenericReturnType()));
        return new Property(name, getterAccessor(m), w, nillable);
    }

    /** The getter if it carries the annotation, else the underlying field if it does, else {@code null}. */
    private static java.lang.reflect.AnnotatedElement annotatedMember(
            Method getter, Field underlying, Class<? extends java.lang.annotation.Annotation> annotationType) {
        Method annotated = overriddenWith(getter, annotationType);
        if (annotated != null) return annotated;
        if (underlying != null && underlying.isAnnotationPresent(annotationType)) return underlying;
        return null;
    }

    /**
     * {@code m}, or the nearest method it overrides (superclasses first, then interfaces), that carries
     * {@code annotationType}: an override without the annotation keeps the customisation of the method it overrides.
     */
    static Method overriddenWith(Method m, Class<? extends java.lang.annotation.Annotation> annotationType) {
        if (m.isAnnotationPresent(annotationType)) return m;
        java.util.ArrayDeque<Class<?>> types = new java.util.ArrayDeque<>();
        for (Class<?> c = m.getDeclaringClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c != m.getDeclaringClass()) types.add(c);
        }
        for (Class<?> c = m.getDeclaringClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            types.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        java.util.Set<Class<?>> visited = new java.util.HashSet<>();
        while (!types.isEmpty()) {
            Class<?> c = types.poll();
            if (!visited.add(c)) continue;
            try {
                Method candidate = c.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (!Modifier.isPrivate(candidate.getModifiers()) && candidate.isAnnotationPresent(annotationType)) {
                    return candidate;
                }
            } catch (NoSuchMethodException _) {
                // not declared there
            }
            if (c.isInterface()) types.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        return null;
    }

    /**
     * P6.1 — prefer LambdaMetafactory for public getters in public classes (standard POJOs), reflection
     * otherwise.
     */
    private static Accessor getterAccessor(Method m) {
        Accessor acc = null;
        if (Modifier.isPublic(m.getModifiers()) && Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            acc = tryLambdaAccessor(m.getDeclaringClass(), m.getName(), m.getReturnType());
        }
        return acc == null ? new MethodAccessor(m) : acc;
    }

    /** A public field is written when it is an instance, non-transient member not covered by a getter nor hidden. */
    private static boolean isFieldCandidate(Field f, java.util.Set<String> getterProps, java.util.Set<String> hiddenProps) {
        int mods = f.getModifiers();
        return !Modifier.isStatic(mods) && !Modifier.isTransient(mods)
                && !f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)
                && !getterProps.contains(f.getName()) && !hiddenProps.contains(f.getName());
    }

    /**
     * Applies the PropertyVisibilityStrategy if one is configured (config or {@code @JsonbVisibility}). §4.6.1: the
     * strategy decides for every field, so the non-public ones are candidates too.
     */
    private void applyVisibilityStrategy(Class<?> type, java.util.Set<String> getterProps,
                                         java.util.Set<String> hiddenProps, List<Property> props) {
        var visibility = effectiveVisibility(type);
        if (visibility == null) return;
        var covered = new java.util.HashSet<>(getterProps);
        for (Property pr : props) {
            if (pr.accessor instanceof FieldAccessor(Field field)) covered.add(field.getName());
        }
        addNonPublicFields(type, covered, hiddenProps, props);
        props.removeIf(pr -> !isVisibleByStrategy(visibility, type, pr));
    }

    /**
     * Adds the non-public instance fields of the hierarchy that no getter nor public field already covers, the
     * most specific declaration of a name first; a field that cannot be made accessible is left out.
     */
    private void addNonPublicFields(Class<?> type, java.util.Set<String> covered, java.util.Set<String> hiddenProps,
                                    List<Property> props) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()
                        && isFieldCandidate(f, covered, hiddenProps) && f.trySetAccessible()) {
                    props.add(fieldProperty(type, f));
                    covered.add(f.getName());
                }
            }
        }
    }

    private Property fieldProperty(Class<?> type, Field f) {
        f.trySetAccessible();
        String name = jsonbName(f, f.getName());
        boolean nillable = computeNillable(null, f, type);
        BindingWriter w = customAdapterWriter(f, null)
                .or(() -> customSerializerWriter(f, null))
                .or(() -> dateWriter(f.getType(), f, type))
                .or(() -> globalDateWriter(f.getType()))
                .or(() -> numberWriter(f.getType(), f, type))
                .orElseGet(() -> writerFor(f.getGenericType()));
        return new Property(name, new FieldAccessor(f), w, nillable);
    }

    /** Detects duplicates on the final JSON name. */
    private static void rejectDuplicateNames(Class<?> type, List<Property> props) {
        var names = new java.util.HashSet<String>();
        for (var pr : props) {
            if (!names.add(pr.name)) {
                throw new JsonbException("Duplicate JSON property name '" + pr.name + "' on " + type);
            }
        }
    }

    private jakarta.json.bind.config.PropertyVisibilityStrategy effectiveVisibility(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (ann != null) {
                try { return ann.value().getDeclaredConstructor().newInstance(); }
                catch (Exception e) { throw new JsonbException("Cannot instantiate @JsonbVisibility " + ann.value(), e); }
            }
        }
        // Walk superclass chain for package visibility (anonymous inner classes inherit from the parent)
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var fromPackage = packageVisibility(c);
            if (fromPackage != null) return fromPackage;
        }
        return propertyVisibilityStrategy;
    }

    /**
     * Reads the {@code @JsonbVisibility} declared on the class package-info.
     * Uses several strategies to work around ClassLoader / module-path cases
     * where {@code Class.getPackage().getAnnotation()} may return null if the
     * package-info has not yet been loaded.
     */
    private jakarta.json.bind.config.PropertyVisibilityStrategy packageVisibility(Class<?> type) {
        var pkg = type.getPackage();
        if (pkg == null) return null;
        ClassLoader cl = type.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        // 1) Explicitly load package-info and read the annotation directly.
        try {
            Class<?> pkgInfo = Class.forName(pkg.getName() + PACKAGE_INFO, false, cl);
            var pann = pkgInfo.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (pann != null) {
                return pann.value().getDeclaredConstructor().newInstance();
            }
        } catch (ClassNotFoundException _) {
            // no package-info class: the next lookups may still find the annotation
        } catch (Exception e) {
            throw new JsonbException("Cannot instantiate package @JsonbVisibility", e);
        }
        // 2) Package.getAnnotation (works only after loading)
        var pann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
        if (pann != null) {
            try { return pann.value().getDeclaredConstructor().newInstance(); }
            catch (Exception e) { throw new JsonbException("Cannot instantiate package @JsonbVisibility " + pann.value(), e); }
        }
        return null;
    }

    private static boolean isVisibleByStrategy(jakarta.json.bind.config.PropertyVisibilityStrategy s, Class<?> type, Property pr) {
        // Spec §4.5: consult BOTH the underlying field (if present) AND the method.
        // A property is visible if either returns true (OR logic).
        if (pr.accessor instanceof FieldAccessor(Field field)) {
            return s.isVisible(field);
        }
        if (pr.accessor instanceof MethodAccessor(Method method)) {
            boolean methodVisible = s.isVisible(method);
            // Look up the underlying field by bean property name
            String propName = beanPropertyOf(method);
            if (propName != null) {
                Field f = findFieldByName(type, propName);
                if (f != null) return methodVisible || s.isVisible(f);
            }
            return methodVisible;
        }
        return true;
    }

    /**
     * Applies the order:
     * <ol>
     *   <li>Properties in @JsonbPropertyOrder in declared order ;</li>
     *   <li>other properties sorted via {@link jakarta.json.bind.config.PropertyOrderStrategy}
     *       (LEXICOGRAPHICAL = default, REVERSE, ANY = insertion order).</li>
     * </ol>
     */
    private void applyPropertyOrder(Class<?> type, List<Property> props) {
        var order = typeAnnotation(type, jakarta.json.bind.annotation.JsonbPropertyOrder.class);
        String[] explicit = order == null ? new String[0] : order.value();
        // Resolve explicit names through naming strategy: @JsonbPropertyOrder names
        // may refer either to the property name (camelCase) or the JSON name. We accept both.
        // Compare the final JSON name; if the user annotated with the JSON name, OK; otherwise try
        // the untransformed property name.
        var byName = new java.util.LinkedHashMap<String, Property>();
        for (var p : props) byName.put(p.name, p);
        var ordered = new ArrayList<Property>(props.size());
        for (String n : explicit) {
            Property p = byName.remove(n);
            if (p == null) {
                // Try with the naming strategy applied
                String transformed = applyNamingStrategy(n, false);
                p = byName.remove(transformed);
            }
            if (p != null) ordered.add(p);
        }
        var rest = new ArrayList<>(byName.values());
        String strat = propertyOrderStrategy;
        // Default JSON-B 3.0 §4.4: LEXICOGRAPHICAL.
        if (strat == null) strat = "LEXICOGRAPHICAL";
        // Group by declaring class (parent → child) for class hierarchy ordering
        var byClass = new java.util.LinkedHashMap<Class<?>, java.util.List<Property>>();
        // Build the super→sub chain
        var chain = new ArrayList<Class<?>>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) chain.add(c);
        java.util.Collections.reverse(chain);
        for (Class<?> c : chain) byClass.put(c, new ArrayList<>());
        for (Property pr : rest) {
            Class<?> dc = declaringClassOf(pr, type);
            byClass.computeIfAbsent(dc, k -> new ArrayList<>()).add(pr);
        }
        for (var group : byClass.values()) {
            sortGroup(group, strat);
            ordered.addAll(group);
        }
        props.clear();
        props.addAll(ordered);
    }

    private static void sortGroup(List<Property> group, String strategy) {
        switch (strategy) {
            case "LEXICOGRAPHICAL" -> group.sort(java.util.Comparator.comparing(p -> p.name));
            case "REVERSE" -> group.sort(java.util.Comparator.<Property, String>comparing(p -> p.name).reversed());
            default -> {
                // ANY (and any unknown strategy): keep the declaration order.
            }
        }
    }

    private static Class<?> declaringClassOf(Property pr, Class<?> fallback) {
        if (pr.accessor instanceof FieldAccessor(Field field)) return field.getDeclaringClass();
        if (pr.accessor instanceof MethodAccessor(Method method)) {
            Class<?> declaring = method.getDeclaringClass();
            return declaring.isInterface() ? firstImplementor(declaring, fallback) : declaring;
        }
        return fallback;
    }

    /** The most general class of {@code type}'s superclass chain that implements {@code itf}: its default getters sort with it. */
    private static Class<?> firstImplementor(Class<?> itf, Class<?> type) {
        Class<?> found = type;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (itf.isAssignableFrom(c)) found = c;
        }
        return found;
    }

    /**
     * Applies the JSON-B 3.0 §4.1.1 naming strategy to a name already determined by
     * bean convention (or by @JsonbProperty/JsonbPropertyOrder, which take priority).
     *
     * <p>If {@code annotated} is true (the name comes from an explicit annotation),
     * no transformation is applied — the annotation has absolute priority.</p>
     */
    String applyNamingStrategy(String name, boolean annotated) {
        if (annotated || propertyNamingStrategy == null) return name;
        return transformName(name, propertyNamingStrategy);
    }

    static String transformName(String name, String strategy) {
        return switch (strategy) {
            case "IDENTITY", "CASE_INSENSITIVE" -> name;
            case "LOWER_CASE_WITH_DASHES" -> camelToDelimited(name, '-');
            case "LOWER_CASE_WITH_UNDERSCORES" -> camelToDelimited(name, '_');
            case "UPPER_CAMEL_CASE" -> name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            case "UPPER_CAMEL_CASE_WITH_SPACES" -> {
                String upper = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                yield insertSpaces(upper);
            }
            default -> name;
        };
    }

    private static String camelToDelimited(String s, char delim) {
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append(delim);
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String insertSpaces(String s) {
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    String propertyOrderStrategy() { return propertyOrderStrategy; }

    jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy() { return propertyVisibilityStrategy; }

    /**
     * JSON-B 3.0 §4.7: if {@code @JsonbTransient} appears on a member (field/getter/setter)
     * and ANOTHER Jsonb annotation appears on the same member OR on the paired (field/getter/setter)
     * member of the same property, it's an invalid combination → JsonbException.
     */
    static void validateTransientCombinations(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                validateTransientProperty(type, c, f);
            }
        }
    }

    private static void validateTransientProperty(Class<?> type, Class<?> declaring, Field f) {
        String prop = f.getName();
        Method getter = findAccessor(declaring, prop, true);
        Method setter = findAccessor(declaring, prop, false);
        boolean anyTransient = carriesJsonbTransient(f) || carriesJsonbTransient(getter) || carriesJsonbTransient(setter);
        if (!anyTransient) return;
        for (var member : new java.lang.reflect.AnnotatedElement[] {f, getter, setter}) {
            var other = member == null ? null : otherJsonbAnnotation(member);
            if (other != null) {
                throw new JsonbException("JSON-B §4.7 : property '" + prop + "' on " + type
                        + " has @JsonbTransient combined with " + other.getSimpleName());
            }
        }
    }

    private static boolean carriesJsonbTransient(java.lang.reflect.AnnotatedElement member) {
        return member != null && member.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    /** The first JSON-B annotation of {@code member} other than {@code @JsonbTransient}, or {@code null}. */
    private static Class<? extends java.lang.annotation.Annotation> otherJsonbAnnotation(java.lang.reflect.AnnotatedElement member) {
        for (var ann : member.getAnnotations()) {
            var atype = ann.annotationType();
            if (atype.getPackageName().startsWith("jakarta.json.bind")
                    && atype != jakarta.json.bind.annotation.JsonbTransient.class) {
                return atype;
            }
        }
        return null;
    }

    private static Method findAccessor(Class<?> c, String prop, boolean isGetter) {
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Method m : c.getDeclaredMethods()) {
            if (isGetter) {
                if ((m.getName().equals("get" + cap) || m.getName().equals("is" + cap)) && m.getParameterCount() == 0) return m;
            } else {
                if (m.getName().equals("set" + cap) && m.getParameterCount() == 1) return m;
            }
        }
        return null;
    }

    /**
     * Calculates the nillable status of a property:
     * <ol>
     *   <li>@JsonbProperty(nillable=true) or explicit @JsonbNillable → true (annotation wins)</li>
     *   <li>@JsonbNillable(false) explicite → false (annotation gagne)</li>
     *   <li>type level @JsonbNillable → true</li>
     *   <li>package level @JsonbNillable → true</li>
     *   <li>JsonbConfig.NULL_VALUES → true</li>
     *   <li>Sinon → false (default : omit)</li>
     * </ol>
     */
    private boolean computeNillable(Method getter, Field underlying, Class<?> type) {
        // Direct annotations on the member, then on the type chain, then on the packages.
        var direct = directNillable(getter).or(() -> directNillable(underlying));
        if (direct.isPresent()) return direct.get();
        var ann = typeAnnotation(type, jakarta.json.bind.annotation.JsonbNillable.class);
        if (ann == null) ann = packageAnnotation(type, jakarta.json.bind.annotation.JsonbNillable.class);
        return ann == null ? writeNullValues : ann.value();
    }

    private static java.util.Optional<Boolean> directNillable(java.lang.reflect.AnnotatedElement el) {
        if (el == null) return java.util.Optional.empty();
        var n = el.getAnnotation(jakarta.json.bind.annotation.JsonbNillable.class);
        if (n != null) return java.util.Optional.of(n.value());
        var p = el.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (p != null && p.nillable()) return java.util.Optional.of(Boolean.TRUE);
        return java.util.Optional.empty();
    }

    /** Looks up a field (public, protected, package, private) on the class or its parents. */
    private static Field findFieldByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException _) { /* not declared here: look in the superclass */ }
        }
        return null;
    }

    /**
     * Looks up {@code @JsonbTypeInfo} on {@code type} or its supertypes (interfaces and
     * superclasses). Spec §4.8: it may be declared on the parent sealed interface.
     *
     * <p>For a linear inheritance chain (A → B → C where each interface carries
     * @JsonbTypeInfo), returns the CLOSEST annotation (the most specific one).
     * Throws {@link JsonbException} only when multiple sibling interfaces carry
     * the annotation independently (true multiple inheritance — TCK
     * {@code TypeInfoExceptionsTest.testSerializeTypeInfoMultiInheritance}).</p>
     */
    static jakarta.json.bind.annotation.JsonbTypeInfo findTypeInfo(Class<?> type) {
        if (type == null || type == Object.class) return null;
        var direct = type.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
        if (direct != null) return direct;
        var fromParents = typeInfoOfDirectParents(type);
        if (fromParents != null) return fromParents;
        // No direct annotation: search recursively through the ancestors.
        for (Class<?> i : type.getInterfaces()) {
            var f = findTypeInfo(i);
            if (f != null) return f;
        }
        return findTypeInfo(type.getSuperclass());
    }

    /**
     * The {@code @JsonbTypeInfo} carried by the direct interfaces and the superclass of {@code type}, or
     * {@code null}. If several of them carry a different one, multiple inheritance is not supported.
     */
    private static jakarta.json.bind.annotation.JsonbTypeInfo typeInfoOfDirectParents(Class<?> type) {
        java.util.List<Class<?>> parents = new java.util.ArrayList<>(java.util.List.of(type.getInterfaces()));
        if (type.getSuperclass() != null && type.getSuperclass() != Object.class) parents.add(type.getSuperclass());
        jakarta.json.bind.annotation.JsonbTypeInfo found = null;
        for (Class<?> parent : parents) {
            var d = parent.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (d != null && found != null && d != found) {
                throw new JsonbException("Multi-inheritance of @JsonbTypeInfo is not supported on " + type.getName());
            }
            if (d != null) found = d;
        }
        return found;
    }

    /**
     * Collects the full {@code @JsonbTypeInfo} chain in
     * <strong>parent → child</strong> order (most general first). Used to write
     * cascading discriminators (MultipleTypeInfoTest).
     */
    static java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> typeInfoChain(Class<?> type) {
        java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> chain = new java.util.ArrayList<>();
        // Linear walk: at each level, take the closest direct annotation
        // (from type toward ancestors) and add it to the chain.
        Class<?> current = type;
        while (current != null && current != Object.class) {
            var direct = current.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (direct != null) chain.add(0, direct); // parent first
            // Look in direct interfaces
            for (Class<?> i : current.getInterfaces()) {
                addChainFromInterface(i, chain);
            }
            current = current.getSuperclass();
        }
        return chain;
    }

    private static void addChainFromInterface(Class<?> i, java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> chain) {
        var direct = i.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
        if (direct != null && !chain.contains(direct)) chain.add(0, direct);
        for (Class<?> p : i.getInterfaces()) {
            addChainFromInterface(p, chain);
        }
    }

    /**
     * §4.8 validations for {@code @JsonbTypeInfo} before writing/reading:
     * <ul>
     *   <li>each alias must point to an assignable subtype</li>
     *   <li>the {@code key} must not collide with a class property name</li>
     * </ul>
     */
    static void validateTypeInfo(Class<?> type, jakarta.json.bind.annotation.JsonbTypeInfo info) {
        if (info == null) return;
        // Find the class that directly CARRIES the annotation.
        Class<?> bearer = bearerOfTypeInfo(type, info);
        if (bearer != null) {
            for (var sub : info.value()) {
                if (!bearer.isAssignableFrom(sub.type())) {
                    throw new JsonbException("@JsonbSubtype alias '" + sub.alias() + "' references "
                            + sub.type().getName() + " which is not a subtype of " + bearer.getName());
                }
            }
        }
        // Check collision of the discriminator key with a class property.
        String key = info.key();
        if (hasInstanceProperty(type, key)) {
            throw new JsonbException("@JsonbTypeInfo key '" + key
                    + "' collides with a property on " + type.getName());
        }
    }

    /** True if {@code type} has a public instance field or getter for the property {@code name}. */
    private static boolean hasInstanceProperty(Class<?> type, String name) {
        for (java.lang.reflect.Field f : type.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getName().equals(name)) return true;
        }
        for (Method m : type.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && name.equals(beanPropertyOf(m))) return true;
        }
        return false;
    }

    /**
     * Looks in {@code info.value()} for the alias corresponding to a subtype
     * assignable from {@code concrete}. Returns null if there is no match (which
     * can happen for intermediate levels in a cascade, e.g. Animal/Dog interfaces
     * without a direct subtype).
     */
    private static String aliasFor(jakarta.json.bind.annotation.JsonbTypeInfo info, Class<?> concrete) {
        for (var sub : info.value()) {
            if (sub.type().isAssignableFrom(concrete)) return sub.alias();
        }
        return null;
    }

    private static Class<?> bearerOfTypeInfo(Class<?> type, jakarta.json.bind.annotation.JsonbTypeInfo info) {
        if (type == null || type == Object.class) return null;
        if (type.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class) == info) return type;
        for (Class<?> i : type.getInterfaces()) {
            var b = bearerOfTypeInfo(i, info);
            if (b != null) return b;
        }
        return bearerOfTypeInfo(type.getSuperclass(), info);
    }

    /**
     * Polymorphic writer: for each {@code value}, identifies the matching alias in
     * {@code typeInfo.value()}, writes {@code @key:alias}, then serializes the
     * members of the concrete class.
     */
    private BindingWriter polymorphicWriter(jakarta.json.bind.annotation.JsonbTypeInfo info) {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            Class<?> concrete = value.getClass();
            // §4.8 validations on the first annotation encountered.
            validateTypeInfo(concrete, info);
            // Cascade: write each (key, alias) pair from the @JsonbTypeInfo chain
            // from the most general ancestor to the most specific child
            // (MultipleTypeInfoTest.testMultipleTypeInfoPropertySerialization).
            g.writeStartObject();
            for (var ann : typeInfoChain(concrete)) {
                String alias = aliasFor(ann, concrete);
                if (alias != null) g.write(ann.key(), alias);
            }
            for (Property p : concretePropertiesOf(concrete)) {
                Object v = readProperty(p, value, "Failed property ");
                writeMember(g, p, v, v == null || v instanceof java.util.Optional<?> opt && opt.isEmpty());
            }
            g.writeEnd();
        };
    }

    private List<Property> concretePropertiesOf(Class<?> concrete) {
        if (concrete.isRecord()) return propertiesFromRecord(concrete);
        return propertiesFromPojo(concrete);
    }

    private List<Property> propertiesFromRecord(Class<?> type) {
        RecordComponent[] comps = type.getRecordComponents();
        var props = new ArrayList<Property>(comps.length);
        for (RecordComponent c : comps) {
            if (isJsonbTransient(c)) continue;
            Method accessor = c.getAccessor();
            // §R-6 — record accessors are ALWAYS public. publicLookup() resolves
            // them without setAccessible or opens on the consumer side.
            // P6.1 — prefer LambdaMetafactory (Function<Object,Object>) which
            // produces inline-friendly code equivalent to a direct getter after
            // warmup; fallback to MethodHandle then reflection.
            Accessor acc = tryLambdaAccessor(type, accessor.getName(), c.getType());
            if (acc == null) {
                try {
                    java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles.publicLookup()
                            .findVirtual(type, accessor.getName(),
                                    java.lang.invoke.MethodType.methodType(c.getType()));
                    acc = new MhAccessor(mh);
                } catch (NoSuchMethodException | IllegalAccessException _) {
                    // Reflection fallback if publicLookup fails (non-exported record).
                    accessor.trySetAccessible();
                    acc = new MethodAccessor(accessor);
                }
            }
            String name = jsonbName(c, c.getName());
            boolean nillable = isJsonbNillable(c) || writeNullValues;
            BindingWriter w = customAdapterWriter(c)
                    .or(() -> customDateWriter(c))
                    .or(() -> globalDateWriter(c.getType()))
                    .orElseGet(() -> writerFor(c.getGenericType()));
            props.add(new Property(name, acc, w, nillable));
        }
        return props;
    }

    private List<Property> propertiesFromPojo(Class<?> type) {
        var props = new ArrayList<Property>();
        var seen = new java.util.HashSet<String>();
        for (Method m : type.getMethods()) {
            boolean candidate = !Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() != Object.class
                    && m.getParameterCount() == 0 && m.getReturnType() != void.class
                    && !m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
            String propName = candidate ? beanPropertyOf(m) : null;
            if (propName != null) {
                seen.add(propName);
                props.add(polymorphicGetterProperty(m, propName));
            }
        }
        for (Field f : type.getFields()) {
            if (isFieldCandidate(f, seen, java.util.Set.of())) props.add(polymorphicFieldProperty(f));
        }
        // Apply parent → child + lex ordering (hierarchical case with
        // @JsonbTypeInfo, MultipleTypeInfoTest.testSerializeMultipleTypeInfoInSingleChain).
        applyPropertyOrder(type, props);
        return props;
    }

    private Property polymorphicGetterProperty(Method m, String propName) {
        m.trySetAccessible();
        String name = jsonbNameFromMethod(m, propName);
        boolean nillable = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class) || writeNullValues;
        BindingWriter w = globalDateWriter(m.getReturnType()).orElseGet(() -> writerFor(m.getGenericReturnType()));
        return new Property(name, getterAccessor(m), w, nillable);
    }

    private Property polymorphicFieldProperty(Field f) {
        f.trySetAccessible();
        String name = jsonbName(f, f.getName());
        boolean nillable = f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class) || writeNullValues;
        BindingWriter w = globalDateWriter(f.getType()).orElseGet(() -> writerFor(f.getGenericType()));
        return new Property(name, new FieldAccessor(f), w, nillable);
    }

    /**
     * True if the {@link RecordComponent} or its accessor carries {@code @JsonbTransient}.
     * Spec §4.7: the annotation may be present on the component itself or on the
     * accessor method (synthetic for records).
     */
    private static boolean isJsonbTransient(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) return true;
        Method accessor = c.getAccessor();
        return accessor.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    /**
     * True if the component or its accessor carries {@code @JsonbNillable}.
     * Spec §4.3.3: forces writing the member even when its value is null.
     */
    private static boolean isJsonbNillable(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class)) return true;
        Method accessor = c.getAccessor();
        return accessor.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class);
    }

    /** Renaming via {@code @JsonbProperty(name)} on a component; fallback {@code defaultName}. */
    private String jsonbName(RecordComponent c, String defaultName) {
        var prop = c.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        var accessorProp = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (accessorProp != null && !accessorProp.value().isEmpty()) return applyNamingStrategy(accessorProp.value(), true);
        return applyNamingStrategy(defaultName, false);
    }

    /** Renaming via {@code @JsonbProperty(name)} on a field; fallback {@code defaultName}. */
    private String jsonbName(Field f, String defaultName) {
        var prop = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        return applyNamingStrategy(defaultName, false);
    }

    /** Renaming via {@code @JsonbProperty(name)} on a method, its paired setter, or the underlying field. */
    String jsonbNameFromMethod(Method m, String defaultName) {
        Method annotated = overriddenWith(m, jakarta.json.bind.annotation.JsonbProperty.class);
        var prop = annotated == null ? null : annotated.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        // Also search the paired setter and the underlying field (spec §4.1.2).
        Class<?> declaring = m.getDeclaringClass();
        Field underlying = findFieldByName(declaring, defaultName);
        if (underlying != null) {
            var fp = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (fp != null && !fp.value().isEmpty()) return applyNamingStrategy(fp.value(), true);
        }
        // Paired setter
        for (Method other : declaring.getDeclaredMethods()) {
            if (other.getName().equals("set" + Character.toUpperCase(defaultName.charAt(0)) + defaultName.substring(1))
                    && other.getParameterCount() == 1) {
                var sp = other.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
                if (sp != null && !sp.value().isEmpty()) return applyNamingStrategy(sp.value(), true);
                break;
            }
        }
        return applyNamingStrategy(defaultName, false);
    }

    /**
     * JavaBean convention §3.7: {@code getXxx} → {@code xxx}; {@code isXxx} (boolean
     * only) → {@code xxx}. Returns {@code null} if the method is not an accessor.
     */
    static String beanPropertyOf(Method m) {
        String n = m.getName();
        if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
            return decapitalize(n.substring(3));
        }
        if (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
            return decapitalize(n.substring(2));
        }
        return null;
    }

    /** JavaBean convention: {@code setXxx} → {@code xxx}. */
    static String beanSetterOf(Method m) {
        String n = m.getName();
        if (n.startsWith("set") && n.length() > 3 && Character.isUpperCase(n.charAt(3))
                && m.getParameterCount() == 1) {
            return decapitalize(n.substring(3));
        }
        return null;
    }

    private static String decapitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static void writeObject(JsonGenerator g, Object value, List<Property> props) {
        if (value == null) { g.writeNull(); return; }
        g.writeStartObject();
        for (Property p : props) {
            if (!writePrimitive(g, p, value)) {
                Object v = readProperty(p, value, "Failed to read property ");
                // Spec §3.14.2: null members omitted by default; Optional.empty() = semantic absence.
                writeMember(g, p, v, isAbsent(v));
            }
        }
        g.writeEnd();
    }

    /**
     * P6.2 — primitive fast path (no boxing): primitives cannot be null, they are emitted directly.
     * The instanceof order follows the frequency of the types. Returns false for any other accessor.
     */
    private static boolean writePrimitive(JsonGenerator g, Property p, Object value) {
        Accessor accessor = p.accessor;
        if (accessor instanceof LongAccessor(var fn)) {
            g.write(p.name, fn.applyAsLong(value));
            return true;
        }
        if (accessor instanceof IntAccessor(var fn)) {
            g.write(p.name, fn.applyAsInt(value));
            return true;
        }
        if (accessor instanceof DoubleAccessor(var fn)) {
            g.write(p.name, fn.applyAsDouble(value));
            return true;
        }
        if (accessor instanceof BooleanAccessor(var fn)) {
            g.write(p.name, fn.test(value));
            return true;
        }
        return false;
    }

    private static Object readProperty(Property p, Object target, String failure) {
        try {
            return p.accessor.read(target);
        } catch (Throwable t) {
            throw new JsonbException(failure + p.name, t);
        }
    }

    /** A null value or an empty {@code Optional}, {@code OptionalInt}, {@code OptionalLong} or {@code OptionalDouble}. */
    private static boolean isAbsent(Object v) {
        // An instanceof chain rather than a pattern switch: this runs for every member written.
        return v == null
                || v instanceof java.util.Optional<?> opt && opt.isEmpty()
                || v instanceof java.util.OptionalInt oi && oi.isEmpty()
                || v instanceof java.util.OptionalLong ol && ol.isEmpty()
                || v instanceof java.util.OptionalDouble od && od.isEmpty();
    }

    /** Writes a member, or, when its value is absent, a JSON null only if the property is nillable (§4.3.3). */
    private static void writeMember(JsonGenerator g, Property p, Object v, boolean absent) {
        if (absent) {
            if (p.nillable) {
                g.writeKey(p.name);
                g.writeNull();
            }
            return;
        }
        g.writeKey(p.name);
        p.writer.write(g, v);
    }

    @FunctionalInterface
    private interface Accessor {
        Object read(Object target) throws Throwable;
    }

    private record MethodAccessor(Method m) implements Accessor {
        public Object read(Object target) throws Throwable { return m.invoke(target); }
    }

    /**
     * Accessor via {@link java.lang.invoke.MethodHandle} — used for records
     * (public canonical accessors) to avoid {@code setAccessible} and {@code opens}.
     * {@code invoke} on a pre-bound MethodHandle is faster than {@code Method.invoke}
     * and compatible with strict Java Modules.
     */
    private record MhAccessor(java.lang.invoke.MethodHandle mh) implements Accessor {
        public Object read(Object target) throws Throwable { return mh.invoke(target); }
    }

    /**
     * P6.1 — Accessor via {@link java.util.function.UnaryOperator} produced by
     * {@link java.lang.invoke.LambdaMetafactory}. HotSpot inlines this site as
     * a direct getter call after warmup; no varargs cast or intermediate boxing
     * (except the automatic valueOf on primitive returns).
     * Fallback to {@link MhAccessor} or {@link MethodAccessor} if the public
     * lookup cannot generate the lambda (class/package not accessible).
     */
    private record LambdaAccessor(java.util.function.UnaryOperator<Object> f) implements Accessor {
        public Object read(Object target) { return f.apply(target); }
    }

    /**
     * P6.2 — Primitive-typed accessors via {@link java.lang.invoke.LambdaMetafactory}
     * on the standard {@code java.util.function} SAMs ({@code ToLongFunction},
     * {@code ToIntFunction}, {@code ToDoubleFunction}, {@code Predicate}).
     * The {@link #writeObject} hot path does an {@code instanceof} and calls
     * {@code applyAsLong/Int/Double} or {@code test} directly, emitting the
     * primitive value without boxing.
     * {@link #read(Object)} remains compatible (boxing via {@code Long.valueOf}
     * etc.) for polymorphic paths (visibility, ordering, etc.).
     */
    private record LongAccessor(java.util.function.ToLongFunction<Object> fn) implements Accessor {
        public Object read(Object target) { return Long.valueOf(fn.applyAsLong(target)); }
    }
    private record IntAccessor(java.util.function.ToIntFunction<Object> fn) implements Accessor {
        public Object read(Object target) { return Integer.valueOf(fn.applyAsInt(target)); }
    }
    private record DoubleAccessor(java.util.function.ToDoubleFunction<Object> fn) implements Accessor {
        public Object read(Object target) { return Double.valueOf(fn.applyAsDouble(target)); }
    }
    private record BooleanAccessor(java.util.function.Predicate<Object> fn) implements Accessor {
        public Object read(Object target) { return Boolean.valueOf(fn.test(target)); }
    }

    /**
     * Attempts to produce a {@link LambdaAccessor} via {@link java.lang.invoke.LambdaMetafactory}.
     * For primitive types ({@code long}/{@code int}/{@code double}/{@code boolean}),
     * prefers a typed accessor ({@link LongAccessor}/{@link IntAccessor}/
     * {@link DoubleAccessor}/{@link BooleanAccessor}) that avoids boxing in the
     * {@code writeObject} hot path (P6.2).
     * Returns {@code null} if the class is not publicly accessible or if the
     * metafactory fails (non-exported class, etc.).
     */
    @SuppressWarnings("unchecked")
    private static Accessor tryLambdaAccessor(Class<?> declaring, String methodName, Class<?> returnType) {
        try {
            var lookup = java.lang.invoke.MethodHandles.publicLookup();
            var implMethod = lookup.findVirtual(declaring, methodName,
                    java.lang.invoke.MethodType.methodType(returnType));
            var instMethodType = java.lang.invoke.MethodType.methodType(returnType, declaring);

            // P6.2 — primitive fast path (no boxing) based on the return type.
            if (returnType == long.class) {
                var samType = java.lang.invoke.MethodType.methodType(long.class, Object.class);
                var cs = java.lang.invoke.LambdaMetafactory.metafactory(lookup, "applyAsLong",
                        java.lang.invoke.MethodType.methodType(java.util.function.ToLongFunction.class),
                        samType, implMethod, instMethodType);
                var fn = (java.util.function.ToLongFunction<Object>) cs.getTarget().invokeExact();
                return new LongAccessor(fn);
            }
            if (returnType == int.class) {
                var samType = java.lang.invoke.MethodType.methodType(int.class, Object.class);
                var cs = java.lang.invoke.LambdaMetafactory.metafactory(lookup, "applyAsInt",
                        java.lang.invoke.MethodType.methodType(java.util.function.ToIntFunction.class),
                        samType, implMethod, instMethodType);
                var fn = (java.util.function.ToIntFunction<Object>) cs.getTarget().invokeExact();
                return new IntAccessor(fn);
            }
            if (returnType == double.class) {
                var samType = java.lang.invoke.MethodType.methodType(double.class, Object.class);
                var cs = java.lang.invoke.LambdaMetafactory.metafactory(lookup, "applyAsDouble",
                        java.lang.invoke.MethodType.methodType(java.util.function.ToDoubleFunction.class),
                        samType, implMethod, instMethodType);
                var fn = (java.util.function.ToDoubleFunction<Object>) cs.getTarget().invokeExact();
                return new DoubleAccessor(fn);
            }
            if (returnType == boolean.class) {
                var samType = java.lang.invoke.MethodType.methodType(boolean.class, Object.class);
                var cs = java.lang.invoke.LambdaMetafactory.metafactory(lookup, "test",
                        java.lang.invoke.MethodType.methodType(java.util.function.Predicate.class),
                        samType, implMethod, instMethodType);
                var fn = (java.util.function.Predicate<Object>) cs.getTarget().invokeExact();
                return new BooleanAccessor(fn);
            }

            // Generic Object → Object path (for String, complex types, Optional…).
            // Automatic boxing for the primitives not covered above
            // (short, byte, float, char) via valueOf inserted by LMF.
            var samMethodType = java.lang.invoke.MethodType.methodType(Object.class, Object.class);
            var callsite = java.lang.invoke.LambdaMetafactory.metafactory(
                    lookup,
                    "apply",
                    java.lang.invoke.MethodType.methodType(java.util.function.UnaryOperator.class),
                    samMethodType,
                    implMethod,
                    instMethodType);
            var fn = (java.util.function.UnaryOperator<Object>) callsite.getTarget().invokeExact();
            return new LambdaAccessor(fn);
        } catch (Throwable _) {
            return null; // no lambda accessor: the caller falls back to a method handle
        }
    }

    private record FieldAccessor(Field f) implements Accessor {
        public Object read(Object target) throws Throwable { return f.get(target); }
    }

    private record Property(String name, Accessor accessor, BindingWriter writer, boolean nillable) {
        Property(String name, Accessor accessor, BindingWriter writer) {
            this(name, accessor, writer, false);
        }
    }

    // ============================================================
    // Built-in writers
    // ============================================================

    private static final class Builtins {

        static final BindingWriter OBJECT = (g, v) -> {
            if (v == null) g.writeNull(); else g.write(v.toString());
        };

        static final BindingWriter STRING = (g, v) -> { if (v == null) g.writeNull(); else g.write((String) v); };

        static final BindingWriter INT = (g, v) -> g.write(((Number) v).intValue());
        static final BindingWriter LONG = (g, v) -> g.write(((Number) v).longValue());
        static final BindingWriter DOUBLE = (g, v) -> g.write(((Number) v).doubleValue());
        static final BindingWriter FLOAT = (g, v) -> g.write(new BigDecimal(Float.toString(((Number) v).floatValue())));
        static final BindingWriter SHORT = (g, v) -> g.write(((Number) v).intValue());
        static final BindingWriter BYTE = (g, v) -> g.write(((Number) v).intValue());
        static final BindingWriter BIG_DECIMAL = (g, v) -> g.write((BigDecimal) v);
        static final BindingWriter BIG_INTEGER = (g, v) -> g.write((BigInteger) v);

        static final BindingWriter BOOLEAN = (g, v) -> g.write((Boolean) v);
        static final BindingWriter CHAR = (g, v) -> g.write(String.valueOf(v));

        static final BindingWriter UUID_W = (g, v) -> g.write(((UUID) v).toString());
        static final BindingWriter URI_W = (g, v) -> g.write(v.toString());
        static final BindingWriter URL_W = (g, v) -> g.write(v.toString());
        static final BindingWriter PATH_W = (g, v) -> g.write(v.toString());
        static final BindingWriter OPT_INT = (g, v) -> {
            var o = (java.util.OptionalInt) v;
            if (o.isPresent()) g.write(o.getAsInt()); else g.writeNull();
        };
        static final BindingWriter OPT_LONG = (g, v) -> {
            var o = (java.util.OptionalLong) v;
            if (o.isPresent()) g.write(o.getAsLong()); else g.writeNull();
        };
        static final BindingWriter OPT_DOUBLE = (g, v) -> {
            var o = (java.util.OptionalDouble) v;
            if (o.isPresent()) g.write(o.getAsDouble()); else g.writeNull();
        };
        static final BindingWriter ENUM = (g, v) -> g.write(((Enum<?>) v).name());

        static final BindingWriter INSTANT = (g, v) -> g.write(((Instant) v).toString());
        static final BindingWriter LOCAL_DATE = (g, v) -> g.write(((LocalDate) v).format(DateTimeFormatter.ISO_LOCAL_DATE));
        static final BindingWriter LOCAL_TIME = (g, v) -> g.write(((java.time.LocalTime) v).format(DateTimeFormatter.ISO_LOCAL_TIME));
        static final BindingWriter LOCAL_DATETIME = (g, v) -> g.write(((LocalDateTime) v).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        static final BindingWriter OFFSET_DATETIME = (g, v) -> g.write(((OffsetDateTime) v).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        static final BindingWriter OFFSET_TIME = (g, v) -> g.write(((java.time.OffsetTime) v).format(DateTimeFormatter.ISO_OFFSET_TIME));
        static final BindingWriter ZONED_DATETIME = (g, v) -> g.write(((ZonedDateTime) v).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
        static final BindingWriter DURATION = (g, v) -> g.write(((java.time.Duration) v).toString());
        static final BindingWriter PERIOD = (g, v) -> g.write(((java.time.Period) v).toString());
        static final BindingWriter ZONE_ID = (g, v) -> g.write(((java.time.ZoneId) v).getId());
        static final BindingWriter ZONE_OFFSET = (g, v) -> g.write(((java.time.ZoneOffset) v).getId());
        static final BindingWriter MONTH_DAY = (g, v) -> g.write(((java.time.MonthDay) v).toString());
        static final BindingWriter YEAR_MONTH = (g, v) -> g.write(((java.time.YearMonth) v).toString());
        static final BindingWriter YEAR = (g, v) -> g.write(((java.time.Year) v).toString());
        // JSON-B 3.0 §3.5.1: java.util.Date → ZonedDateTime UTC ISO format
        static final BindingWriter UTIL_DATE = (g, v) -> {
            var d = (java.util.Date) v;
            g.write(d.toInstant().atZone(java.time.ZoneId.of("UTC")).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
        };
        static final BindingWriter CALENDAR = (g, v) -> {
            var c = (java.util.Calendar) v;
            var zdt = c.toInstant().atZone(c.getTimeZone().toZoneId());
            // JSON-B 3.0 §3.5.1: if time = 00:00:00.000, use ISO_OFFSET_DATE format.
            if (zdt.getHour() == 0 && zdt.getMinute() == 0 && zdt.getSecond() == 0 && zdt.getNano() == 0) {
                g.write(zdt.toLocalDate().atStartOfDay(zdt.getZone()).toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE));
            } else {
                g.write(zdt.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            }
        };
        static final BindingWriter TIMEZONE = (g, v) -> g.write(((java.util.TimeZone) v).getID());

        // JSON-B §3.6: JSON-P types go through g.write(JsonValue)
        static final BindingWriter JSON_VALUE = (g, v) -> g.write((jakarta.json.JsonValue) v);

        /** Writers of the types matched exactly (final classes and primitives). */
        private static final java.util.Map<Class<?>, BindingWriter> EXACT = java.util.Map.ofEntries(
                java.util.Map.entry(String.class, STRING),
                java.util.Map.entry(Integer.class, INT), java.util.Map.entry(int.class, INT),
                java.util.Map.entry(Long.class, LONG), java.util.Map.entry(long.class, LONG),
                java.util.Map.entry(Double.class, DOUBLE), java.util.Map.entry(double.class, DOUBLE),
                java.util.Map.entry(Float.class, FLOAT), java.util.Map.entry(float.class, FLOAT),
                java.util.Map.entry(Short.class, SHORT), java.util.Map.entry(short.class, SHORT),
                java.util.Map.entry(Byte.class, BYTE), java.util.Map.entry(byte.class, BYTE),
                java.util.Map.entry(Boolean.class, BOOLEAN), java.util.Map.entry(boolean.class, BOOLEAN),
                java.util.Map.entry(Character.class, CHAR), java.util.Map.entry(char.class, CHAR),
                java.util.Map.entry(BigDecimal.class, BIG_DECIMAL),
                java.util.Map.entry(BigInteger.class, BIG_INTEGER),
                java.util.Map.entry(UUID.class, UUID_W),
                java.util.Map.entry(java.net.URI.class, URI_W),
                java.util.Map.entry(java.net.URL.class, URL_W),
                java.util.Map.entry(java.util.OptionalInt.class, OPT_INT),
                java.util.Map.entry(java.util.OptionalLong.class, OPT_LONG),
                java.util.Map.entry(java.util.OptionalDouble.class, OPT_DOUBLE),
                java.util.Map.entry(Instant.class, INSTANT),
                java.util.Map.entry(LocalDate.class, LOCAL_DATE),
                java.util.Map.entry(java.time.LocalTime.class, LOCAL_TIME),
                java.util.Map.entry(LocalDateTime.class, LOCAL_DATETIME),
                java.util.Map.entry(OffsetDateTime.class, OFFSET_DATETIME),
                java.util.Map.entry(java.time.OffsetTime.class, OFFSET_TIME),
                java.util.Map.entry(ZonedDateTime.class, ZONED_DATETIME),
                java.util.Map.entry(java.time.Duration.class, DURATION),
                java.util.Map.entry(java.time.Period.class, PERIOD),
                java.util.Map.entry(java.time.MonthDay.class, MONTH_DAY),
                java.util.Map.entry(java.time.YearMonth.class, YEAR_MONTH),
                java.util.Map.entry(java.time.Year.class, YEAR));

        static BindingWriter lookup(Class<?> type) {
            if (jakarta.json.JsonValue.class.isAssignableFrom(type)) return JSON_VALUE;
            BindingWriter exact = EXACT.get(type);
            if (exact != null) return exact;
            // Then the types matched with their subtypes, the most specific first.
            if (java.nio.file.Path.class.isAssignableFrom(type)) return PATH_W;
            if (java.time.ZoneOffset.class.isAssignableFrom(type)) return ZONE_OFFSET;
            if (java.time.ZoneId.class.isAssignableFrom(type)) return ZONE_ID;
            if (java.util.Calendar.class.isAssignableFrom(type)) return CALENDAR;
            if (java.util.Date.class.isAssignableFrom(type)) return UTIL_DATE;
            if (java.util.TimeZone.class.isAssignableFrom(type)) return TIMEZONE;
            return null;
        }
    }
}
