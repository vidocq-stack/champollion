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

    private final String defaultDateFormat;
    private final boolean writeNullValues;
    private final String binaryDataStrategy;
    private final String propertyNamingStrategy;
    private final String propertyOrderStrategy;
    private final jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy;
    private final java.util.Locale configLocale;
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> globalAdapters;
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> globalSerializers = new java.util.LinkedHashMap<>();
    private final ChampollionSerializationContext serContext = new ChampollionSerializationContext(this);

    @SuppressWarnings("rawtypes")
    void setGlobalSerializers(java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbSerializer> map) {
        this.globalSerializers.clear();
        if (map != null) this.globalSerializers.putAll(map);
    }

    RuntimeBindingRegistry() {
        this(null, false, null, null, null, null, null, java.util.Map.of());
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues) {
        this(defaultDateFormat, writeNullValues, null, null, null, null, null, java.util.Map.of());
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy) {
        this(defaultDateFormat, writeNullValues, binaryDataStrategy, null, null, null, null, java.util.Map.of());
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy,
                           String propertyNamingStrategy, String propertyOrderStrategy,
                           jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy) {
        this(defaultDateFormat, writeNullValues, binaryDataStrategy, propertyNamingStrategy, propertyOrderStrategy, propertyVisibilityStrategy, null, java.util.Map.of());
    }

    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy,
                           String propertyNamingStrategy, String propertyOrderStrategy,
                           jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                           java.util.Locale configLocale) {
        this(defaultDateFormat, writeNullValues, binaryDataStrategy, propertyNamingStrategy, propertyOrderStrategy, propertyVisibilityStrategy, configLocale, java.util.Map.of());
    }

    @SuppressWarnings("rawtypes")
    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues, String binaryDataStrategy,
                           String propertyNamingStrategy, String propertyOrderStrategy,
                           jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                           java.util.Locale configLocale,
                           java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> globalAdapters) {
        this.defaultDateFormat = defaultDateFormat;
        this.writeNullValues = writeNullValues;
        this.binaryDataStrategy = binaryDataStrategy;
        this.propertyNamingStrategy = propertyNamingStrategy;
        this.propertyOrderStrategy = propertyOrderStrategy;
        this.propertyVisibilityStrategy = propertyVisibilityStrategy;
        this.configLocale = configLocale;
        this.globalAdapters = globalAdapters == null ? java.util.Map.of() : globalAdapters;
    }

    private final ClassValue<BindingWriter> cache = new ClassValue<>() {
        @Override protected BindingWriter computeValue(Class<?> type) { return resolveClass(type); }
    };

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
        // Exact match first, then assignable.
        var direct = globalAdapters.get(raw);
        if (direct == null) {
            for (var entry : globalAdapters.entrySet()) {
                if (entry.getKey().isAssignableFrom(raw)) { direct = entry.getValue(); break; }
            }
        }
        if (direct == null) return null;
        final jakarta.json.bind.adapter.JsonbAdapter adapter = direct;
        java.lang.reflect.Type adaptedGeneric = findAdaptedGenericType((Class<? extends jakarta.json.bind.adapter.JsonbAdapter>) adapter.getClass());
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
        var direct = globalSerializers.get(raw);
        if (direct == null) {
            for (var entry : globalSerializers.entrySet()) {
                if (entry.getKey().isAssignableFrom(raw)) { direct = entry.getValue(); break; }
            }
        }
        if (direct == null) return null;
        final jakarta.json.bind.serializer.JsonbSerializer ser = direct;
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            ser.serialize(value, g, serContext);
        };
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

    private BindingWriter arrayWriter(Class<?> componentType) {
        if (componentType == byte.class) return byteArrayWriter();
        if (componentType == int.class) return (g, v) -> {
            g.writeStartArray();
            for (int x : (int[]) v) g.write(x);
            g.writeEnd();
        };
        if (componentType == long.class) return (g, v) -> {
            g.writeStartArray();
            for (long x : (long[]) v) g.write(x);
            g.writeEnd();
        };
        if (componentType == double.class) return (g, v) -> {
            g.writeStartArray();
            for (double x : (double[]) v) g.write(x);
            g.writeEnd();
        };
        if (componentType == float.class) return (g, v) -> {
            g.writeStartArray();
            for (float x : (float[]) v) g.write((double) x);
            g.writeEnd();
        };
        if (componentType == short.class) return (g, v) -> {
            g.writeStartArray();
            for (short x : (short[]) v) g.write((int) x);
            g.writeEnd();
        };
        if (componentType == char.class) return (g, v) -> {
            g.writeStartArray();
            for (char x : (char[]) v) g.write(String.valueOf(x));
            g.writeEnd();
        };
        if (componentType == boolean.class) return (g, v) -> {
            g.writeStartArray();
            for (boolean x : (boolean[]) v) g.write(x);
            g.writeEnd();
        };
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
            for (byte b : (byte[]) v) g.write((int) b);
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

    private BindingWriter resolveClass(Class<?> type) {
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
                try { accessor.setAccessible(true); } catch (Exception ignore) {}
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
        if ("##default".equals(spec.pattern()) || spec.pattern().isEmpty()) {
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
        if (member != null) {
            var ann = member.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
            if (ann != null) return new NumberFormatSpec(ann.value(), parseLocale(ann.locale()));
        }
        if (declaringType != null) {
            for (Class<?> c = declaringType; c != null && c != Object.class; c = c.getSuperclass()) {
                var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
                if (ann != null) return new NumberFormatSpec(ann.value(), parseLocale(ann.locale()));
            }
            for (Class<?> c = declaringType; c != null && c != Object.class; c = c.getSuperclass()) {
                var pkg = c.getPackage();
                if (pkg == null) continue;
                ClassLoader cl = c.getClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                try { Class.forName(pkg.getName() + ".package-info", false, cl); } catch (Throwable ignored) {}
                var ann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
                if (ann != null) return new NumberFormatSpec(ann.value(), parseLocale(ann.locale()));
            }
        }
        return null;
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
        boolean isDefault() { return "##default".equals(pattern); }
    }

    static DateFormatSpec findDateFormatSpec(java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        if (member != null) {
            var ann = member.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
            if (ann != null) return new DateFormatSpec(ann.value(), parseLocale(ann.locale()));
        }
        if (declaringType != null) {
            for (Class<?> c = declaringType; c != null && c != Object.class; c = c.getSuperclass()) {
                var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
                if (ann != null) return new DateFormatSpec(ann.value(), parseLocale(ann.locale()));
            }
            // Walk superclass packages (anonymous inner classes inherit from the parent)
            for (Class<?> c = declaringType; c != null && c != Object.class; c = c.getSuperclass()) {
                var pkg = c.getPackage();
                if (pkg == null) continue;
                ClassLoader cl = c.getClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                try { Class.forName(pkg.getName() + ".package-info", false, cl); }
                catch (Throwable ignored) {}
                var ann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
                if (ann != null) return new DateFormatSpec(ann.value(), parseLocale(ann.locale()));
            }
        }
        return null;
    }

    private static java.util.Locale parseLocale(String tag) {
        // §4.7.3: "##default" on @JsonbNumberFormat / @JsonbDateFormat means the
        // canonical JSON-B format (group separator ',' decimal '.'), equivalent to Locale.ROOT.
        if (tag == null || tag.isEmpty() || "##default".equals(tag)) return java.util.Locale.ROOT;
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
            if (c == '\'') { inLiteral = !inLiteral; continue; }
            if (inLiteral) continue;
            if (c == 'x' || c == 'O' || c == 'V') return true;
        }
        return false;
    }

    private BindingWriter makeDateWriter(Class<?> rawType, DateFormatSpec spec) {
        if (java.util.Date.class.isAssignableFrom(rawType)) {
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
        if (java.util.Calendar.class.isAssignableFrom(rawType)) {
            return (g, value) -> {
                if (value == null) { g.writeNull(); return; }
                var cal = (java.util.Calendar) value;
                if (spec.isDefault()) {
                    var zdt = cal.toInstant().atZone(cal.getTimeZone().toZoneId());
                    g.write(zdt.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME));
                } else {
                    // Convert via ZonedDateTime to align with the ZonedDateTime UTC format
                    // (strict IJSON §3.5.1 requires Date/Calendar to produce the same
                    // format as a ZonedDateTime).
                    var zdt = cal.toInstant().atZone(cal.getTimeZone().toZoneId());
                    var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
                    g.write(fmt.format(zdt));
                }
            };
        }
        // Duration / Period: fixed ISO 8601 format — DateTimeFormatter patterns do not apply.
        if (rawType == java.time.Duration.class || rawType == java.time.Period.class) {
            return (g, value) -> {
                if (value == null) { g.writeNull(); return; }
                g.write(value.toString());
            };
        }
        // java.time: convert to ZonedDateTime UTC when needed to absorb patterns
        // that require absolute fields (yyyy/MM/dd HH:mm:ss).
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            if (spec.isDefault()) {
                g.write(value.toString());
                return;
            }
            var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
            java.time.temporal.TemporalAccessor t;
            if (value instanceof java.time.LocalDate ld) {
                t = ld.atStartOfDay(java.time.ZoneOffset.UTC);
            } else if (value instanceof java.time.LocalDateTime ldt) {
                t = ldt.atZone(java.time.ZoneOffset.UTC);
            } else if (value instanceof java.time.LocalTime lt) {
                t = lt.atDate(java.time.LocalDate.of(1970, 1, 1)).atZone(java.time.ZoneOffset.UTC);
            } else if (value instanceof java.time.Instant inst) {
                t = inst.atZone(java.time.ZoneOffset.UTC);
            } else if (value instanceof java.time.temporal.TemporalAccessor ta) {
                t = ta;
            } else {
                throw new JsonbException("Cannot format type with date pattern: " + value.getClass());
            }
            g.write(fmt.format(t));
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
        var props = new ArrayList<Property>();
        var seen = new java.util.HashSet<String>();

        // 1) Discover all getters in the hierarchy (including private/protected/package).
        // JSON-B 3.0 §3.7.1: only public getters are considered by default, but
        // the EXISTENCE of a non-public accessor hides the property (a public field is not enough).
        var getterByProp = new java.util.LinkedHashMap<String, Method>();
        var hiddenProps = new java.util.HashSet<String>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isStatic(mods)) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == void.class) continue;
                String propName = beanPropertyOf(m);
                if (propName == null) continue;
                if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
                    hiddenProps.add(propName);
                    continue;
                }
                if (!Modifier.isPublic(mods)) {
                    // Non-public getter → the property is hidden (even if a public field exists)
                    hiddenProps.add(propName);
                    continue;
                }
                // §3.7.1: if the underlying field is static or transient, skip.
                Field underlying = findFieldByName(type, propName);
                if (underlying != null) {
                    int fMods = underlying.getModifiers();
                    if (Modifier.isStatic(fMods) || Modifier.isTransient(fMods)) {
                        hiddenProps.add(propName);
                        continue;
                    }
                    if (underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
                        hiddenProps.add(propName);
                        continue;
                    }
                }
                Method existing = getterByProp.get(propName);
                if (existing == null) {
                    getterByProp.put(propName, m);
                } else {
                    boolean existingIsGet = existing.getName().startsWith("get");
                    boolean newIsGet = m.getName().startsWith("get");
                    if (newIsGet && !existingIsGet) getterByProp.put(propName, m);
                    else if (existing.getReturnType().isAssignableFrom(m.getReturnType())
                            && existing.getReturnType() != m.getReturnType()) {
                        getterByProp.put(propName, m);
                    }
                }
            }
        }
        // Removes hidden props in case both public/non-public are present
        getterByProp.keySet().removeAll(hiddenProps);
        for (var e : getterByProp.entrySet()) {
            String propName = e.getKey();
            Method m = e.getValue();
            try { m.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbNameFromMethod(m, propName);
            Field underlying0 = findFieldByName(type, propName);
            boolean nillable = computeNillable(m, underlying0, type);
            // Date / Number format: @JsonbDateFormat / @JsonbNumberFormat on method, underlying field, type, package, or global config.
            Field underlying = findFieldByName(type, propName);
            java.lang.reflect.AnnotatedElement memberForDate = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class)
                    ? m : (underlying != null && underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class) ? underlying : null);
            java.lang.reflect.AnnotatedElement memberForNumber = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class)
                    ? m : (underlying != null && underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class) ? underlying : null);
            // §4.7 — @JsonbTypeAdapter / @JsonbTypeSerializer on the getter or its underlying field.
            final Field underlying2 = underlying;
            BindingWriter w = customAdapterWriter(m, underlying2)
                    .or(() -> customSerializerWriter(m, underlying2))
                    .or(() -> dateWriter(m.getReturnType(), memberForDate, type))
                    .or(() -> globalDateWriter(m.getReturnType()))
                    .or(() -> numberWriter(m.getReturnType(), memberForNumber, type))
                    .orElseGet(() -> writerFor(m.getGenericReturnType()));
            seen.add(propName);
            // P6.1 — prefer LambdaMetafactory for public getters
            // in public classes (standard POJOs).
            Accessor pojoAcc = (Modifier.isPublic(m.getModifiers())
                    && Modifier.isPublic(m.getDeclaringClass().getModifiers()))
                    ? tryLambdaAccessor(m.getDeclaringClass(), m.getName(), m.getReturnType())
                    : null;
            if (pojoAcc == null) pojoAcc = new MethodAccessor(m);
            props.add(new Property(name, pojoAcc, w, nillable));
        }

        // 2) Public fields not covered by a getter and not hidden.
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            if (f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            if (seen.contains(f.getName())) continue;
            if (hiddenProps.contains(f.getName())) continue;
            try { f.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbName(f, f.getName());
            boolean nillable = computeNillable(null, f, type);
            BindingWriter w = customAdapterWriter(f, null)
                    .or(() -> customSerializerWriter(f, null))
                    .or(() -> dateWriter(f.getType(), f, type))
                    .or(() -> globalDateWriter(f.getType()))
                    .or(() -> numberWriter(f.getType(), f, type))
                    .orElseGet(() -> writerFor(f.getGenericType()));
            props.add(new Property(name, new FieldAccessor(f), w, nillable));
        }

        // Apply PropertyVisibilityStrategy if configured (config or @JsonbVisibility).
        var visibility = effectiveVisibility(type);
        if (visibility != null) {
            var visibleProps = new ArrayList<Property>(props.size());
            for (Property pr : props) {
                if (isVisibleByStrategy(visibility, type, pr)) visibleProps.add(pr);
            }
            props.clear();
            props.addAll(visibleProps);
        }

        // Detect duplicates on the final JSON name.
        var names = new java.util.HashSet<String>();
        for (var pr : props) {
            if (!names.add(pr.name)) {
                throw new JsonbException("Duplicate JSON property name '" + pr.name + "' on " + type);
            }
        }

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
            Class<?> pkgInfo = Class.forName(pkg.getName() + ".package-info", false, cl);
            var pann = pkgInfo.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (pann != null) {
                return pann.value().getDeclaredConstructor().newInstance();
            }
        } catch (ClassNotFoundException ignored) {
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
        if (pr.accessor instanceof FieldAccessor fa) {
            return s.isVisible(fa.f);
        }
        if (pr.accessor instanceof MethodAccessor ma) {
            boolean methodVisible = s.isVisible(ma.m);
            // Look up the underlying field by bean property name
            String propName = beanPropertyOf(ma.m);
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
        jakarta.json.bind.annotation.JsonbPropertyOrder order = null;
        for (Class<?> c = type; c != null && c != Object.class && order == null; c = c.getSuperclass()) {
            order = c.getAnnotation(jakarta.json.bind.annotation.JsonbPropertyOrder.class);
        }
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
        var sortedRest = new ArrayList<Property>(rest.size());
        for (var e : byClass.entrySet()) {
            var group = e.getValue();
            switch (strat) {
                case "LEXICOGRAPHICAL" -> group.sort(java.util.Comparator.comparing(p -> p.name));
                case "REVERSE" -> group.sort(java.util.Comparator.<Property, String>comparing(p -> p.name).reversed());
                case "ANY" -> { }
                default -> { }
            }
            sortedRest.addAll(group);
        }
        ordered.addAll(sortedRest);
        props.clear();
        props.addAll(ordered);
    }

    private static Class<?> declaringClassOf(Property pr, Class<?> fallback) {
        if (pr.accessor instanceof FieldAccessor fa) return fa.f.getDeclaringClass();
        if (pr.accessor instanceof MethodAccessor ma) return ma.m.getDeclaringClass();
        return fallback;
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
                String prop = f.getName();
                Method getter = findAccessor(c, prop, true);
                Method setter = findAccessor(c, prop, false);
                boolean fieldTransient = f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
                boolean getterTransient = getter != null && getter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
                boolean setterTransient = setter != null && setter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
                if (!fieldTransient && !getterTransient && !setterTransient) continue;
                java.util.List<java.lang.reflect.AnnotatedElement> targets = new java.util.ArrayList<>();
                targets.add(f);
                if (getter != null) targets.add(getter);
                if (setter != null) targets.add(setter);
                for (var t : targets) {
                    for (var ann : t.getAnnotations()) {
                        var atype = ann.annotationType();
                        if (!atype.getPackageName().startsWith("jakarta.json.bind")) continue;
                        if (atype == jakarta.json.bind.annotation.JsonbTransient.class) continue;
                        throw new JsonbException("JSON-B §4.7 : property '" + prop + "' on " + type
                                + " has @JsonbTransient combined with " + atype.getSimpleName());
                    }
                }
            }
        }
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
        // Direct annotations on the member
        Boolean direct = directNillable(getter);
        if (direct != null) return direct;
        if (underlying != null) {
            Boolean d2 = directNillable(underlying);
            if (d2 != null) return d2;
        }
        // Type chain @JsonbNillable
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbNillable.class);
            if (ann != null) return ann.value();
        }
        // Package @JsonbNillable (walk superclass packages too)
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var pkg = c.getPackage();
            if (pkg == null) continue;
            ClassLoader cl = c.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            try { Class.forName(pkg.getName() + ".package-info", false, cl); } catch (Throwable ignored) {}
            var ann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbNillable.class);
            if (ann != null) return ann.value();
        }
        return writeNullValues;
    }

    private static Boolean directNillable(java.lang.reflect.AnnotatedElement el) {
        if (el == null) return null;
        var n = el.getAnnotation(jakarta.json.bind.annotation.JsonbNillable.class);
        if (n != null) return n.value();
        var p = el.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (p != null && p.nillable()) return true;
        return null;
    }

    /** Looks up a field (public, protected, package, private) on the class or its parents. */
    private static Field findFieldByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
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
        // Collect from direct interfaces AND the superclass.
        java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> direct1 = new java.util.ArrayList<>();
        for (Class<?> i : type.getInterfaces()) {
            var d = i.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (d != null) direct1.add(d);
        }
        if (type.getSuperclass() != null && type.getSuperclass() != Object.class) {
            var d = type.getSuperclass().getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (d != null) direct1.add(d);
        }
        // If multiple direct ancestors carry @JsonbTypeInfo, multiple inheritance is not supported.
        if (direct1.size() > 1) {
            var first = direct1.get(0);
            for (int i = 1; i < direct1.size(); i++) {
                if (direct1.get(i) != first) {
                    throw new JsonbException("Multi-inheritance of @JsonbTypeInfo is not supported on " + type.getName());
                }
            }
            return first;
        }
        if (direct1.size() == 1) return direct1.get(0);
        // No direct annotation: search recursively through the ancestors.
        for (Class<?> i : type.getInterfaces()) {
            var f = findTypeInfo(i);
            if (f != null) return f;
        }
        return findTypeInfo(type.getSuperclass());
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
        for (java.lang.reflect.Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)) continue;
            if (f.getName().equals(key)) {
                throw new JsonbException("@JsonbTypeInfo key '" + key
                        + "' collides with a property on " + type.getName());
            }
        }
        for (Method m : type.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            String prop = beanPropertyOf(m);
            if (prop != null && prop.equals(key)) {
                throw new JsonbException("@JsonbTypeInfo key '" + key
                        + "' collides with a property on " + type.getName());
            }
        }
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
            var chain = typeInfoChain(concrete);
            g.writeStartObject();
            for (var ann : chain) {
                String alias = aliasFor(ann, concrete);
                if (alias != null) g.write(ann.key(), alias);
            }
            List<Property> props = concretePropertiesOf(concrete);
            for (Property p : props) {
                Object v;
                try { v = p.accessor.read(value); }
                catch (Throwable t) { throw new JsonbException("Failed property " + p.name, t); }
                if (v == null) {
                    if (p.nillable) { g.writeKey(p.name); g.writeNull(); }
                    continue;
                }
                if (v instanceof java.util.Optional<?> opt && opt.isEmpty()) {
                    if (p.nillable) { g.writeKey(p.name); g.writeNull(); }
                    continue;
                }
                g.writeKey(p.name);
                p.writer.write(g, v);
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
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    // Reflection fallback if publicLookup fails (non-exported record).
                    try { accessor.setAccessible(true); } catch (Exception ignore) {}
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
            int mods = m.getModifiers();
            if (Modifier.isStatic(mods)) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) continue;
            String propName = beanPropertyOf(m);
            if (propName == null) continue;
            if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            try { m.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbNameFromMethod(m, propName);
            boolean nillable = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class) || writeNullValues;
            BindingWriter w = globalDateWriter(m.getReturnType()).orElseGet(() -> writerFor(m.getGenericReturnType()));
            seen.add(propName);
            Accessor pojoAcc = (Modifier.isPublic(m.getModifiers())
                    && Modifier.isPublic(m.getDeclaringClass().getModifiers()))
                    ? tryLambdaAccessor(m.getDeclaringClass(), m.getName(), m.getReturnType())
                    : null;
            if (pojoAcc == null) pojoAcc = new MethodAccessor(m);
            props.add(new Property(name, pojoAcc, w, nillable));
        }
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            if (f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            if (seen.contains(f.getName())) continue;
            try { f.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbName(f, f.getName());
            boolean nillable = f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class) || writeNullValues;
            BindingWriter w = globalDateWriter(f.getType()).orElseGet(() -> writerFor(f.getGenericType()));
            props.add(new Property(name, new FieldAccessor(f), w, nillable));
        }
        // Apply parent → child + lex ordering (hierarchical case with
        // @JsonbTypeInfo, MultipleTypeInfoTest.testSerializeMultipleTypeInfoInSingleChain).
        applyPropertyOrder(type, props);
        return props;
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
        var prop = m.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
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
            // P6.2 — primitive fast path (no boxing).
            // Primitives cannot be null → emit directly.
            // The instanceof order follows the frequency of the types.
            if (p.accessor instanceof LongAccessor la) {
                g.write(p.name, la.fn.applyAsLong(value));
                continue;
            }
            if (p.accessor instanceof IntAccessor ia) {
                g.write(p.name, ia.fn.applyAsInt(value));
                continue;
            }
            if (p.accessor instanceof DoubleAccessor da) {
                g.write(p.name, da.fn.applyAsDouble(value));
                continue;
            }
            if (p.accessor instanceof BooleanAccessor ba) {
                g.write(p.name, ba.fn.test(value));
                continue;
            }
            Object v;
            try {
                v = p.accessor.read(value);
            } catch (Throwable t) {
                throw new JsonbException("Failed to read property " + p.name, t);
            }
            if (v == null) {
                // Spec §3.14.2: null members omitted by default.
                // §4.3.3 @JsonbNillable: force-include.
                if (p.nillable) {
                    g.writeKey(p.name);
                    g.writeNull();
                }
                continue;
            }
            if (v instanceof java.util.Optional<?> opt && opt.isEmpty()) {
                // Optional.empty() = semantic absence → omit member (unless @JsonbNillable).
                if (p.nillable) {
                    g.writeKey(p.name);
                    g.writeNull();
                }
                continue;
            }
            if (v instanceof java.util.OptionalInt oi && oi.isEmpty()) {
                if (p.nillable) { g.writeKey(p.name); g.writeNull(); }
                continue;
            }
            if (v instanceof java.util.OptionalLong ol && ol.isEmpty()) {
                if (p.nillable) { g.writeKey(p.name); g.writeNull(); }
                continue;
            }
            if (v instanceof java.util.OptionalDouble od && od.isEmpty()) {
                if (p.nillable) { g.writeKey(p.name); g.writeNull(); }
                continue;
            }
            g.writeKey(p.name);
            p.writer.write(g, v);
        }
        g.writeEnd();
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
     * and compatible with strict JPMS.
     */
    private record MhAccessor(java.lang.invoke.MethodHandle mh) implements Accessor {
        public Object read(Object target) throws Throwable { return mh.invoke(target); }
    }

    /**
     * P6.1 — Accessor via {@link java.util.function.Function} produced by
     * {@link java.lang.invoke.LambdaMetafactory}. HotSpot inlines this site as
     * a direct getter call after warmup; no varargs cast or intermediate boxing
     * (except the automatic valueOf on primitive returns).
     * Fallback to {@link MhAccessor} or {@link MethodAccessor} if the public
     * lookup cannot generate the lambda (class/package not accessible).
     */
    private record LambdaAccessor(java.util.function.Function<Object, Object> f) implements Accessor {
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
                    java.lang.invoke.MethodType.methodType(java.util.function.Function.class),
                    samMethodType,
                    implMethod,
                    instMethodType);
            var fn = (java.util.function.Function<Object, Object>) callsite.getTarget().invokeExact();
            return new LambdaAccessor(fn);
        } catch (Throwable t) {
            return null;
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
        static final BindingWriter CHAR = (g, v) -> g.write(String.valueOf((Character) v));

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

        static BindingWriter lookup(Class<?> type) {
            if (jakarta.json.JsonValue.class.isAssignableFrom(type)) return JSON_VALUE;
            if (type == String.class) return STRING;
            if (type == Integer.class || type == int.class) return INT;
            if (type == Long.class || type == long.class) return LONG;
            if (type == Double.class || type == double.class) return DOUBLE;
            if (type == Float.class || type == float.class) return FLOAT;
            if (type == Short.class || type == short.class) return SHORT;
            if (type == Byte.class || type == byte.class) return BYTE;
            if (type == Boolean.class || type == boolean.class) return BOOLEAN;
            if (type == Character.class || type == char.class) return CHAR;
            if (type == BigDecimal.class) return BIG_DECIMAL;
            if (type == BigInteger.class) return BIG_INTEGER;
            if (type == UUID.class) return UUID_W;
            if (type == java.net.URI.class) return URI_W;
            if (type == java.net.URL.class) return URL_W;
            if (java.nio.file.Path.class.isAssignableFrom(type)) return PATH_W;
            if (type == java.util.OptionalInt.class) return OPT_INT;
            if (type == java.util.OptionalLong.class) return OPT_LONG;
            if (type == java.util.OptionalDouble.class) return OPT_DOUBLE;
            if (type == Instant.class) return INSTANT;
            if (type == LocalDate.class) return LOCAL_DATE;
            if (type == java.time.LocalTime.class) return LOCAL_TIME;
            if (type == LocalDateTime.class) return LOCAL_DATETIME;
            if (type == OffsetDateTime.class) return OFFSET_DATETIME;
            if (type == java.time.OffsetTime.class) return OFFSET_TIME;
            if (type == ZonedDateTime.class) return ZONED_DATETIME;
            if (type == java.time.Duration.class) return DURATION;
            if (type == java.time.Period.class) return PERIOD;
            if (type == java.time.MonthDay.class) return MONTH_DAY;
            if (type == java.time.YearMonth.class) return YEAR_MONTH;
            if (type == java.time.Year.class) return YEAR;
            if (java.time.ZoneOffset.class.isAssignableFrom(type)) return ZONE_OFFSET;
            if (java.time.ZoneId.class.isAssignableFrom(type)) return ZONE_ID;
            if (java.util.Calendar.class.isAssignableFrom(type)) return CALENDAR;
            if (java.util.Date.class.isAssignableFrom(type)) return UTIL_DATE;
            if (java.util.TimeZone.class.isAssignableFrom(type)) return TIMEZONE;
            return null;
        }
    }
}
