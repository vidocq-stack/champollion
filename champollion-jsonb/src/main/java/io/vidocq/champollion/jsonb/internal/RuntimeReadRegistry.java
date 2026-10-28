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
import jakarta.json.stream.JsonParser;

import java.lang.reflect.Constructor;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BindingReader} registry symmetrical to {@link RuntimeBindingRegistry}.
 *
 * <p>{@link ClassValue} cache for simple classes + concurrent map for parameterized
 * types (key = {@link Type#getTypeName()}).</p>
 */
final class RuntimeReadRegistry {

    private final String defaultDateFormat;
    private final String binaryDataStrategy;
    private final String propertyNamingStrategy;
    private final jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy;
    private final java.util.Locale configLocale;
    private final boolean failOnUnknownProperties;
    private final boolean creatorParametersRequired;
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> globalAdapters;
    @SuppressWarnings("rawtypes")
    private final java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbDeserializer> globalDeserializers = new java.util.LinkedHashMap<>();
    private final ChampollionDeserializationContext deserContext = new ChampollionDeserializationContext(this);

    @SuppressWarnings("rawtypes")
    void setGlobalDeserializers(java.util.Map<Class<?>, jakarta.json.bind.serializer.JsonbDeserializer> map) {
        this.globalDeserializers.clear();
        if (map != null) this.globalDeserializers.putAll(map);
    }

    RuntimeReadRegistry() { this(null, null, null, null, null, false, false, java.util.Map.of()); }

    RuntimeReadRegistry(String defaultDateFormat) { this(defaultDateFormat, null, null, null, null, false, false, java.util.Map.of()); }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy) {
        this(defaultDateFormat, binaryDataStrategy, null, null, null, false, false, java.util.Map.of());
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, null, false, false, java.util.Map.of());
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, configLocale, false, false, java.util.Map.of());
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale,
                        boolean failOnUnknownProperties) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, configLocale, failOnUnknownProperties, false, java.util.Map.of());
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale,
                        boolean failOnUnknownProperties,
                        boolean creatorParametersRequired) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, configLocale, failOnUnknownProperties, creatorParametersRequired, java.util.Map.of());
    }

    @SuppressWarnings("rawtypes")
    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale,
                        boolean failOnUnknownProperties,
                        boolean creatorParametersRequired,
                        java.util.Map<Class<?>, jakarta.json.bind.adapter.JsonbAdapter> globalAdapters) {
        this.defaultDateFormat = defaultDateFormat;
        this.binaryDataStrategy = binaryDataStrategy;
        this.propertyNamingStrategy = propertyNamingStrategy;
        this.propertyVisibilityStrategy = propertyVisibilityStrategy;
        this.configLocale = configLocale;
        this.failOnUnknownProperties = failOnUnknownProperties;
        this.creatorParametersRequired = creatorParametersRequired;
        this.globalAdapters = globalAdapters == null ? java.util.Map.of() : globalAdapters;
    }

    /** Readers resolved by raw class. */
    private final ClassValue<BindingReader> classCache = new ClassValue<>() {
        @Override
        protected BindingReader computeValue(Class<?> type) {
            BindingReader b = Builtins.lookup(type);
            if (b != null) return b;
            if (type.isEnum()) return enumReader(type);
            // M4.5: polymorphism — if @JsonbTypeInfo is present, dispatch through the cascade.
            // To follow write order (most general ancestor first), reading starts with the
            // TOP of the chain's @JsonbTypeInfo.
            var chain = RuntimeBindingRegistry.typeInfoChain(type);
            var info = chain.isEmpty() ? null : chain.get(0);
            if (info != null) return polymorphicReader(info);
            if (type.isRecord()) return resolveRecord(type);
            if (type.isPrimitive()) return get(box(type));
            if (java.util.Map.class.isAssignableFrom(type)) return mapReader(RuntimeReadRegistry.this::dynamicValue, type);
            if (java.util.Collection.class.isAssignableFrom(type)) {
                return collectionReader(RuntimeReadRegistry.this::dynamicValue, type);
            }
            if (type == Optional.class) return optionalReader(RuntimeReadRegistry.this::dynamicValue);
            if (type == Object.class) return RuntimeReadRegistry.this::dynamicValue;
            if (type == Number.class) return RuntimeReadRegistry::readNumber;
            if (Modifier.isAbstract(type.getModifiers()) && !type.isInterface()) {
                return RuntimeReadRegistry.this::dynamicValue;
            }
            return resolvePojo(type);
        }
    };

    private final java.util.concurrent.ConcurrentHashMap<String, BindingReader> typeCache = new java.util.concurrent.ConcurrentHashMap<>();

    BindingReader readerFor(Type t) {
        // Global adapter or deserializer registered for this type?
        Class<?> rawAd = rawClassOf(t, null);
        var adapterReader = adapterReaderFor(rawAd);
        if (adapterReader != null) return adapterReader;
        var deserializerReader = deserializerReaderFor(rawAd, t);
        if (deserializerReader != null) return deserializerReader;
        if (t instanceof Class<?> c) {
            if (c.isArray()) return arrayReader(c.getComponentType());
            return classCache.get(c);
        }
        if (t instanceof java.lang.reflect.ParameterizedType p) {
            return typeCache.computeIfAbsent(p.getTypeName(), k -> parameterizedReader(p));
        }
        if (t instanceof java.lang.reflect.GenericArrayType ga) {
            return arrayReader(rawClassOf(ga.getGenericComponentType(), Object.class));
        }
        if (t instanceof java.lang.reflect.TypeVariable<?> || t instanceof java.lang.reflect.WildcardType) {
            return this::dynamicValue;
        }
        return classCache.get((Class<?>) t);
    }

    /** The raw class of a class or parameterized type, {@code otherwise} for any other type. */
    private static Class<?> rawClassOf(Type t, Class<?> otherwise) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType pt) return (Class<?>) pt.getRawType();
        return otherwise;
    }

    /**
     * Finds a global adapter registered via {@code JsonbConfig.withAdapters} whose
     * {@code Original} type is assignable from {@code raw}. Returns a reader that
     * reads the adapted format and then invokes {@code adaptFromJson}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BindingReader adapterReaderFor(Class<?> raw) {
        if (globalAdapters.isEmpty() || raw == null || raw == Object.class) return null;
        var direct = globalAdapters.get(raw);
        if (direct == null) {
            for (var entry : globalAdapters.entrySet()) {
                if (entry.getKey().isAssignableFrom(raw)) { direct = entry.getValue(); break; }
            }
        }
        if (direct == null) return null;
        final jakarta.json.bind.adapter.JsonbAdapter adapter = direct;
        java.lang.reflect.Type adaptedGeneric = RuntimeBindingRegistry.findAdaptedGenericType(adapter.getClass());
        BindingReader inner = readerForGeneric(adaptedGeneric);
        return parser -> {
            Object adaptedValue = inner.read(parser);
            if (adaptedValue == null) return null;
            try { return adapter.adaptFromJson(adaptedValue); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on fromJson: " + ex.getMessage(), ex); }
        };
    }

    /** Variant of {@link #readerFor(Type)} without adapter short-circuiting, to avoid infinite recursion. */
    private BindingReader readerForRaw(Class<?> c) {
        if (c == null || c == Object.class) return this::dynamicValue;
        if (c.isArray()) return arrayReader(c.getComponentType());
        return classCache.get(c);
    }

    /**
     * Finds a global {@code JsonbDeserializer} whose {@code T} type is assignable
     * from {@code raw}. Returns a reader that invokes {@code deserialize(parser, ctx, type)}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BindingReader deserializerReaderFor(Class<?> raw, Type fullType) {
        if (globalDeserializers.isEmpty() || raw == null || raw == Object.class) return null;
        var direct = globalDeserializers.get(raw);
        if (direct == null) {
            for (var entry : globalDeserializers.entrySet()) {
                if (entry.getKey().isAssignableFrom(raw)) { direct = entry.getValue(); break; }
            }
        }
        if (direct == null) return null;
        final jakarta.json.bind.serializer.JsonbDeserializer deser = direct;
        return parser -> deser.deserialize(parser, deserContext, fullType);
    }

    /**
     * Reader for {@code @JsonbTypeDeserializer} on a record component / Method / Field.
     * Default variant: <em>regular property</em> — Champollion advances the parser
     * to the first token of the value before delegating (see spec §10.3
     * "JsonParser positioned on the first token of the value").
     */
    java.util.Optional<BindingReader> customDeserializerReader(java.lang.reflect.AnnotatedElement member,
                                                               java.lang.reflect.Field underlying,
                                                               Type targetType) {
        return customDeserializerReader(member, underlying, targetType, /*advanceParser*/ true);
    }

    /**
     * Explicit variant with parser-advance control.
     *
     * <p>For a <strong>regular property</strong> (setter, field), the parser is
     * positioned just after {@code KEY_NAME}; Champollion calls {@code parser.next()}
     * to advance to the first token of the value (e.g. {@code START_ARRAY},
     * {@code START_OBJECT}, {@code VALUE_STRING}) before invoking {@code deserialize()}
     * — which is what the TCK's {@code AnimalListDeserializerInjected} expects
     * for {@code while (parser.next() == START_OBJECT)}.</p>
     *
     * <p>For a <strong>creator parameter</strong>, the parser stays at
     * {@code KEY_NAME}; the deserializer performs the {@code next()} itself, which
     * is what the TCK's {@code SimpleStringDeserializer}
     * {@code InstantiationCustomizationTest} expects.</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    java.util.Optional<BindingReader> customDeserializerReader(java.lang.reflect.AnnotatedElement member,
                                                               java.lang.reflect.Field underlying,
                                                               Type targetType,
                                                               boolean advanceParser) {
        var ann = member == null ? null : member.getAnnotation(jakarta.json.bind.annotation.JsonbTypeDeserializer.class);
        if (ann == null && underlying != null) {
            ann = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbTypeDeserializer.class);
        }
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.serializer.JsonbDeserializer> dClass = ann.value();
        jakarta.json.bind.serializer.JsonbDeserializer deser;
        try {
            // §5 — CDI resolution if a container is available, otherwise newInstance.
            deser = CdiResolver.resolve(dClass);
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbDeserializer " + dClass, e);
        }
        if (advanceParser) {
            return java.util.Optional.of((BindingReader.UserDeserializer) parser -> {
                parser.next();
                return deser.deserialize(parser, deserContext, targetType);
            });
        }
        return java.util.Optional.of((BindingReader.UserDeserializer) parser -> deser.deserialize(parser, deserContext, targetType));
    }

    /** Variant of {@link #readerFor(Type)} without adapter but preserving {@link java.lang.reflect.ParameterizedType}s. */
    private BindingReader readerForGeneric(java.lang.reflect.Type t) {
        if (t == null) return this::dynamicValue;
        if (t instanceof java.lang.reflect.ParameterizedType p) {
            return typeCache.computeIfAbsent(p.getTypeName(), k -> parameterizedReader(p));
        }
        if (t instanceof java.lang.reflect.GenericArrayType ga) {
            return arrayReader(rawClassOf(ga.getGenericComponentType(), Object.class));
        }
        if (t instanceof Class<?> c) return readerForRaw(c);
        return this::dynamicValue;
    }

    // ===== Resolution by raw class =====

    /** Reads a value declared as {@link Number}: a {@link BigDecimal}, from a JSON number or string. */
    private static Object readNumber(JsonParser parser) {
        JsonParser.Event ev = parser.next();
        return switch (ev) {
            case VALUE_NULL -> null;
            case VALUE_NUMBER -> parser.getBigDecimal();
            case VALUE_STRING -> new BigDecimal(parser.getString());
            default -> throw new JsonbException("Cannot deserialize Number from " + ev);
        };
    }

    private static Class<?> box(Class<?> p) {
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

    private BindingReader resolveRecord(Class<?> type) {
        RecordComponent[] comps = type.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[comps.length];
        BindingReader[] readers = new BindingReader[comps.length];
        Map<String, Integer> indexByName = componentReaders(comps, paramTypes, readers);
        // §R-1/R-5 — a record's canonical constructor is ALWAYS public.
        // publicLookup() resolves it WITHOUT setAccessible or opens on the consumer side
        // for records IN AN EXPORTED PACKAGE. For internal test or non-exported
        // records, fall back to Reflection with setAccessible.
        java.lang.invoke.MethodHandle ctorMh = null;
        try {
            ctorMh = java.lang.invoke.MethodHandles.publicLookup().findConstructor(type,
                    java.lang.invoke.MethodType.methodType(void.class, paramTypes));
        } catch (NoSuchMethodException | IllegalAccessException _) {
            ctorMh = null; // not public: the reflective constructor below takes over
        }
        Constructor<?> reflCtor = null;
        if (ctorMh == null) {
            try {
                reflCtor = type.getDeclaredConstructor(paramTypes);
            } catch (NoSuchMethodException e) {
                throw new JsonbException("Canonical record constructor not found for " + type, e);
            }
            makeAccessible(reflCtor);
        }
        final java.lang.invoke.MethodHandle finalCtorMh = ctorMh;
        final Constructor<?> finalReflCtor = reflCtor;

        return parser -> readObjectAndInvokeMh(parser, finalCtorMh, finalReflCtor, type, paramTypes, readers, indexByName);
    }

    /**
     * Fills the parameter type and the reader of each record component, and returns the index of each
     * component by JSON name ({@code @JsonbTransient} components are read as their default value).
     */
    private Map<String, Integer> componentReaders(RecordComponent[] comps, Class<?>[] paramTypes,
                                                  BindingReader[] readers) {
        Map<String, Integer> indexByName = HashMap.newHashMap(comps.length);
        for (int i = 0; i < comps.length; i++) {
            final RecordComponent comp = comps[i];
            paramTypes[i] = comp.getType();
            // M4.4f @JsonbTypeAdapter > M4.4d @JsonbDateFormat > M4.7 JSONB_DATE_FORMAT > runtime.
            readers[i] = customAdapterReader(comp)
                    .or(() -> customDateReader(comp))
                    .or(() -> globalDateReader(comp.getType()))
                    .orElseGet(() -> readerFor(comp.getGenericType()));
            if (!isJsonbTransient(comp)) indexByName.put(jsonbName(comp), i);
        }
        return indexByName;
    }

    /** Reads the members of an object whose {@code START_OBJECT} is consumed into constructor arguments. */
    private static Object[] readArguments(JsonParser p, Class<?>[] paramTypes, BindingReader[] readers,
                                          Map<String, Integer> indexByName) {
        Object[] args = defaultArguments(paramTypes);
        JsonParser.Event e;
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            Integer idx = indexByName.get(keyName(p, e));
            if (idx == null) skipValue(p);
            else args[idx] = readers[idx].read(p);
        }
        return args;
    }

    /** The default value of each parameter type, for the members absent from the document. */
    private static Object[] defaultArguments(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) args[i] = defaultFor(paramTypes[i]);
        return args;
    }

    /** Consumes the start of an object value: false for a JSON {@code null}, an error for any other value. */
    private static boolean enterObject(JsonParser p) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return false;
        if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object, got " + e);
        return true;
    }

    /** The name of the member that event {@code e} starts, which must be a {@code KEY_NAME}. */
    private static String keyName(JsonParser p, JsonParser.Event e) {
        if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
        return p.getString();
    }

    private Object readObjectAndInvokeMh(JsonParser p, java.lang.invoke.MethodHandle ctorMh,
                                         Constructor<?> reflCtor, Class<?> type, Class<?>[] paramTypes,
                                         BindingReader[] readers, Map<String, Integer> indexByName) {
        if (!enterObject(p)) return null;
        Object[] args = readArguments(p, paramTypes, readers, indexByName);
        try {
            if (ctorMh != null) return ctorMh.invokeWithArguments(args);
            return reflCtor.newInstance(args);
        } catch (Throwable t) {
            throw new JsonbException("Failed to instantiate record " + type + ": " + t.getMessage(), t);
        }
    }

    private BindingReader resolvePojo(Class<?> type) {
        // M4.4e: if @JsonbCreator is on a constructor or static factory, use it.
        var creator = findJsonbCreator(type);
        if (creator != null) return resolveCreator(creator);

        RuntimeBindingRegistry.validateTransientCombinations(type);

        // Fallback: no-arg ctor + public fields.
        Constructor<?> ctor = noArgConstructor(type);
        java.lang.invoke.MethodHandle ctorMh = publicNoArgConstructor(type, ctor);
        if (ctorMh == null) makeAccessible(ctor);

        // Pre-detect duplicate names (same logic as on the writer side).
        validateNoDuplicateNames(type);

        // Discover JavaBean setters (§3.7) + public-field fallback. ALL setters, including
        // non-public ones, are discovered to identify the hidden properties.
        var hiddenProps = new java.util.HashSet<String>();
        Map<String, Method> setterByProp = discoverSetters(type, hiddenProps);
        Map<String, BeanWriter> writersByName = new HashMap<>();
        addSetterWriters(type, setterByProp, writersByName);
        addFieldWriters(type, hiddenProps, writersByName);
        // Apply PropertyVisibilityStrategy (config / @JsonbVisibility / package).
        applyVisibility(type, writersByName);

        return parser -> readObjectAndApply(parser, ctor, ctorMh, type, writersByName);
    }

    /** The no-arg constructor of a bean, which JSON-B 3.0 §3.7.1 wants public or protected without {@code @JsonbCreator}. */
    private static Constructor<?> noArgConstructor(Class<?> type) {
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new JsonbException("No no-arg constructor for " + type
                    + " (consider adding @JsonbCreator on a constructor or static factory).", e);
        }
        int cMods = ctor.getModifiers();
        if (!Modifier.isPublic(cMods) && !Modifier.isProtected(cMods)) {
            throw new JsonbException("No accessible no-arg constructor for " + type
                    + " (consider adding @JsonbCreator).");
        }
        return ctor;
    }

    /**
     * §R-2 — a handle on the constructor of a public POJO through publicLookup (avoids opens on the consumer
     * side), or null when the reflective constructor must be used (protected ctors or non-exported types).
     */
    private static java.lang.invoke.MethodHandle publicNoArgConstructor(Class<?> type, Constructor<?> ctor) {
        if (!Modifier.isPublic(ctor.getModifiers()) || !Modifier.isPublic(type.getModifiers())) return null;
        try {
            return java.lang.invoke.MethodHandles.publicLookup()
                    .findConstructor(type, java.lang.invoke.MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException _) {
            return null;
        }
    }

    /**
     * The public setters of a bean by property, the most specific one when a property has several; the
     * properties of the transient or non-public setters, and of the static or transient fields, go to
     * {@code hiddenProps} and have no setter.
     */
    private static Map<String, Method> discoverSetters(Class<?> type, java.util.Set<String> hiddenProps) {
        Map<String, Method> setterByProp = new java.util.LinkedHashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String prop = setterProperty(m);
                if (prop != null && isHiddenSetter(type, m, prop)) {
                    hiddenProps.add(prop);
                } else if (prop != null) {
                    keepMostSpecificSetter(setterByProp, prop, m);
                }
            }
        }
        setterByProp.keySet().removeAll(hiddenProps);
        return setterByProp;
    }

    /** The property that an instance {@code void} method sets, or null when it is not a setter. */
    private static String setterProperty(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()
                || m.getReturnType() != void.class) {
            return null;
        }
        return RuntimeBindingRegistry.beanSetterOf(m);
    }

    /** Whether a setter hides its property: transient or non-public setter, static or transient underlying field. */
    private static boolean isHiddenSetter(Class<?> type, Method m, String prop) {
        if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)
                || !Modifier.isPublic(m.getModifiers())) {
            return true;
        }
        Field underlying = findFieldByName(type, prop);
        return underlying != null && !isPropertyField(underlying);
    }

    /** Keeps {@code m} for {@code prop} unless a setter with a more specific parameter type is already known. */
    private static void keepMostSpecificSetter(Map<String, Method> setterByProp, String prop, Method m) {
        Method existing = setterByProp.get(prop);
        if (existing == null) {
            setterByProp.put(prop, m);
            return;
        }
        Class<?> ep = existing.getParameterTypes()[0];
        Class<?> np = m.getParameterTypes()[0];
        if (ep.isAssignableFrom(np) && ep != np) setterByProp.put(prop, m);
    }

    /** Whether a field can carry a property: neither static, transient nor {@code @JsonbTransient}. */
    private static boolean isPropertyField(Field f) {
        int mods = f.getModifiers();
        return !Modifier.isStatic(mods) && !Modifier.isTransient(mods)
                && !f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    /** Whether a public field can be read into: a property field that is not final. */
    private static boolean isBindableField(Field f) {
        return isPropertyField(f) && !Modifier.isFinal(f.getModifiers());
    }

    /** 1) Public setters, unless the corresponding getter is {@code @JsonbTransient}. */
    private void addSetterWriters(Class<?> type, Map<String, Method> setterByProp, Map<String, BeanWriter> writersByName) {
        for (var e : setterByProp.entrySet()) {
            String prop = e.getKey();
            Method m = e.getValue();
            Method getter = findGetter(type, prop);
            if (getter == null || !getter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
                makeAccessible(m);
                String name = methodJsonbName(m, getter, prop);
                writersByName.put(name, setterWriter(m, setterReader(type, prop, m, getter)));
            }
        }
    }

    /**
     * The reader of a setter's value: §4.7 {@code @JsonbTypeAdapter} / {@code @JsonbTypeDeserializer} on the
     * setter, the getter or the underlying field, then the date and number formats, then the type's reader.
     */
    private BindingReader setterReader(Class<?> type, String prop, Method m, Method getter) {
        Type valueType = m.getGenericParameterTypes()[0];
        Field underlyingF = findFieldByName(type, prop);
        var adapterR = customAdapterReader(m, underlyingF);
        if (adapterR.isEmpty() && getter != null) adapterR = customAdapterReader(getter, underlyingF);
        if (adapterR.isPresent()) return adapterR.get();
        var deserR = customDeserializerReader(m, underlyingF, valueType);
        if (deserR.isEmpty() && getter != null) deserR = customDeserializerReader(getter, underlyingF, valueType);
        if (deserR.isPresent()) return deserR.get();
        Class<?> paramType = m.getParameterTypes()[0];
        BindingReader dateR = dateReaderFor(paramType, m, getter, type);
        if (dateR != null) return dateR;
        BindingReader numR = numberReaderFor(paramType, m, getter, type);
        return numR != null ? numR : readerFor(valueType);
    }

    /**
     * §R-2 — calls the setter through publicLookup when it is public AND in a public class (strict Java
     * Modules), otherwise falls back to Reflection.
     */
    private static BeanWriter setterWriter(Method m, BindingReader reader) {
        if (Modifier.isPublic(m.getModifiers()) && Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            try {
                var mh = java.lang.invoke.MethodHandles.publicLookup()
                        .findVirtual(m.getDeclaringClass(), m.getName(),
                                java.lang.invoke.MethodType.methodType(void.class, m.getParameterTypes()[0]));
                return new MhSetter(mh, m, reader);
            } catch (NoSuchMethodException | IllegalAccessException _) {
                // Not reachable through publicLookup: the reflective setter below takes over.
            }
        }
        makeAccessible(m);
        return new MethodSetter(m, reader);
    }

    /** 2) Public fields not covered by a setter and not hidden. */
    private void addFieldWriters(Class<?> type, java.util.Set<String> hiddenProps, Map<String, BeanWriter> writersByName) {
        for (Field f : type.getFields()) {
            String name = isBindableField(f) && !hiddenProps.contains(f.getName()) ? jsonbName(f) : null;
            if (name != null && !writersByName.containsKey(name)) {
                makeAccessible(f);
                BindingReader reader = customAdapterReader(f, null)
                        .or(() -> customDeserializerReader(f, null, f.getGenericType()))
                        .orElseGet(() -> readerFor(f.getGenericType()));
                writersByName.put(name, new FieldSetter(f, reader));
            }
        }
    }

    /** Keeps the writers that the effective {@code PropertyVisibilityStrategy}, if any, makes visible. */
    private void applyVisibility(Class<?> type, Map<String, BeanWriter> writersByName) {
        var visibility = effectiveVisibility(type);
        if (visibility != null) {
            writersByName.values().removeIf(bw -> !isVisible(visibility, type, bw));
        }
    }

    /** A setter is visible when the setter or its underlying field is; a field when the field is. */
    private static boolean isVisible(jakarta.json.bind.config.PropertyVisibilityStrategy visibility,
                                     Class<?> type, BeanWriter bw) {
        Method method = setterMethod(bw);
        if (method != null) {
            boolean methodVisible = visibility.isVisible(method);
            String prop = RuntimeBindingRegistry.beanSetterOf(method);
            Field underlying = prop == null ? null : findFieldByName(type, prop);
            return methodVisible || (underlying != null && visibility.isVisible(underlying));
        }
        if (bw instanceof FieldSetter(Field f, var _)) return visibility.isVisible(f);
        return true;
    }

    /** The setter method behind a writer, or null for a field writer. */
    private static Method setterMethod(BeanWriter bw) {
        if (bw instanceof MethodSetter(Method m, var _)) return m;
        if (bw instanceof MhSetter(var _, Method m, var _)) return m;
        return null;
    }

    private jakarta.json.bind.config.PropertyVisibilityStrategy effectiveVisibility(Class<?> type) {
        Class<?> c = type; // never null: the type being bound
        do {
            var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (ann != null) {
                try { return ann.value().getDeclaredConstructor().newInstance(); }
                catch (Exception e) { throw new JsonbException("Cannot instantiate @JsonbVisibility " + ann.value(), e); }
            }
            c = c.getSuperclass();
        } while (c != null && c != Object.class);
        var pkg = type.getPackage();
        if (pkg != null) {
            loadPackageInfo(pkg, type.getClassLoader());
            var pann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (pann != null) {
                try { return pann.value().getDeclaredConstructor().newInstance(); }
                catch (Exception e) { throw new JsonbException("Cannot instantiate package @JsonbVisibility " + pann.value(), e); }
            }
        }
        return propertyVisibilityStrategy;
    }

    /**
     * Detects whether two properties would end up with the same JSON name after
     * applying @JsonbProperty / naming strategy; otherwise throws JsonbException.
     */
    private void validateNoDuplicateNames(Class<?> type) {
        var seen = new java.util.HashMap<String, String>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (isPropertyField(f)) checkUniqueName(type, f, seen);
            }
        }
    }

    /** Records the JSON name of field {@code f}, rejecting a name already taken by another property. */
    private void checkUniqueName(Class<?> type, Field f, Map<String, String> seen) {
        String prop = f.getName();
        String name = jsonbName(f);
        String prev = seen.put(name, prop);
        if (prev != null && !prev.equals(prop)) {
            throw new JsonbException("Duplicate JSON property name '" + name
                    + "' on " + type + " (from '" + prev + "' and '" + prop + "')");
        }
    }

    /**
     * Looks for a date reader for {@code paramType} by consulting @JsonbDateFormat
     * at several levels (setter, getter, field, type, package, config).
     */
    private BindingReader dateReaderFor(Class<?> paramType, Method setter, Method getter, Class<?> declaringType) {
        if (!RuntimeBindingRegistry.isDateLikeType(paramType)) return null;
        // Durations and periods are always ISO 8601, as the writer leaves them: a date pattern does not apply.
        if (paramType == java.time.Duration.class || paramType == java.time.Period.class) return null;
        var member = dateFormatMember(setter, getter, declaringType);
        var spec = RuntimeBindingRegistry.findDateFormatSpec(member, declaringType);
        if (spec == null && defaultDateFormat != null) {
            java.util.Locale loc = configLocale != null ? configLocale : java.util.Locale.getDefault();
            spec = new RuntimeBindingRegistry.DateFormatSpec(defaultDateFormat, loc);
        }
        if (spec == null) return null;
        return makeDateReader(paramType, spec);
    }

    /** The member carrying {@code @JsonbDateFormat} for a property, by priority: setter, getter, underlying field. */
    private static java.lang.reflect.AnnotatedElement dateFormatMember(Method setter, Method getter, Class<?> declaringType) {
        var annotation = jakarta.json.bind.annotation.JsonbDateFormat.class;
        if (setter.isAnnotationPresent(annotation)) return setter;
        if (getter != null && getter.isAnnotationPresent(annotation)) return getter;
        String prop = RuntimeBindingRegistry.beanSetterOf(setter);
        Field f = prop == null ? null : findFieldByName(declaringType, prop);
        return f != null && f.isAnnotationPresent(annotation) ? f : null;
    }

    private BindingReader makeDateReader(Class<?> rawType, RuntimeBindingRegistry.DateFormatSpec spec) {
        if (java.util.Date.class.isAssignableFrom(rawType)) {
            return p -> p.next() == JsonParser.Event.VALUE_NULL ? null : parseDate(p.getString(), spec);
        }
        if (java.util.Calendar.class.isAssignableFrom(rawType)) {
            return p -> p.next() == JsonParser.Event.VALUE_NULL ? null : parseCalendar(p.getString(), spec);
        }
        // java.time
        return p -> p.next() == JsonParser.Event.VALUE_NULL ? null : parseTemporal(rawType, p.getString(), spec);
    }

    private static java.util.Date parseDate(String s, RuntimeBindingRegistry.DateFormatSpec spec) {
        if (spec.isDefault()) return java.util.Date.from(parseAsInstant(s));
        try {
            var sdf = new java.text.SimpleDateFormat(spec.pattern(), spec.locale());
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(s);
        } catch (java.text.ParseException pe) {
            throw new JsonbException("Date parse error: " + pe.getMessage(), pe);
        }
    }

    private static java.util.Calendar parseCalendar(String s, RuntimeBindingRegistry.DateFormatSpec spec) {
        if (spec.isDefault()) return java.util.GregorianCalendar.from(parseAsZonedDateTime(s));
        try {
            var sdf = new java.text.SimpleDateFormat(spec.pattern(), spec.locale());
            var d = sdf.parse(s);
            var cal = new java.util.GregorianCalendar(sdf.getTimeZone());
            cal.setTime(d);
            return cal;
        } catch (java.text.ParseException pe) {
            throw new JsonbException("Date parse error: " + pe.getMessage(), pe);
        }
    }

    /** A {@code java.time} value in its ISO 8601 form, or with the pattern of {@code spec}; the string for other types. */
    private static Object parseTemporal(Class<?> rawType, String s, RuntimeBindingRegistry.DateFormatSpec spec) {
        if (spec.isDefault()) return parseIsoTemporal(rawType, s);
        var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
        if (rawType == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
        if (rawType == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
        if (rawType == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
        if (rawType == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
        if (rawType == java.time.LocalTime.class) return java.time.LocalTime.parse(s, fmt);
        if (rawType == java.time.OffsetTime.class) return java.time.OffsetTime.parse(s, fmt);
        if (rawType == Instant.class) return java.time.ZonedDateTime.parse(s, fmt).toInstant();
        return s;
    }

    private static Object parseIsoTemporal(Class<?> rawType, String s) {
        if (rawType == java.time.LocalDate.class) return java.time.LocalDate.parse(s);
        if (rawType == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s);
        if (rawType == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s);
        if (rawType == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s);
        if (rawType == java.time.LocalTime.class) return java.time.LocalTime.parse(s);
        if (rawType == java.time.OffsetTime.class) return java.time.OffsetTime.parse(s);
        if (rawType == Instant.class) return Instant.parse(s);
        if (rawType == java.time.Duration.class) return java.time.Duration.parse(s);
        if (rawType == java.time.Period.class) return java.time.Period.parse(s);
        return s;
    }

    /** Custom numeric reader via @JsonbNumberFormat. */
    private BindingReader numberReaderFor(Class<?> paramType, Method setter, Method getter, Class<?> declaringType) {
        Class<?> boxed = paramType.isPrimitive() ? boxOfPrim(paramType) : paramType;
        if (!Number.class.isAssignableFrom(boxed)) return null;
        var ann = numberFormatOf(setter, getter, declaringType);
        if (ann == null) return null;
        java.text.NumberFormat fmt = numberFormat(ann);
        return p -> {
            var ev = p.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            String s = p.getString();
            try {
                Number parsed = fmt.parse(s);
                return convertNumber(parsed, boxed);
            } catch (java.text.ParseException pe) {
                throw new JsonbException("Number parse error: " + pe.getMessage(), pe);
            }
        };
    }

    /** {@code @JsonbNumberFormat} of a property, by priority: setter, getter, underlying field, class chain, packages. */
    private static jakarta.json.bind.annotation.JsonbNumberFormat numberFormatOf(Method setter, Method getter,
                                                                                Class<?> declaringType) {
        var annotation = jakarta.json.bind.annotation.JsonbNumberFormat.class;
        var ann = setter.getAnnotation(annotation);
        if (ann == null && getter != null) ann = getter.getAnnotation(annotation);
        if (ann == null) {
            String prop = RuntimeBindingRegistry.beanSetterOf(setter);
            Field f = prop == null ? null : findFieldByName(declaringType, prop);
            ann = f == null ? null : f.getAnnotation(annotation);
        }
        for (Class<?> c = declaringType; c != null && c != Object.class && ann == null; c = c.getSuperclass()) {
            ann = c.getAnnotation(annotation);
        }
        for (Class<?> c = declaringType; c != null && c != Object.class && ann == null; c = c.getSuperclass()) {
            ann = packageNumberFormat(c);
        }
        return ann;
    }

    /** {@code @JsonbNumberFormat} of the package of {@code c}, if any. */
    private static jakarta.json.bind.annotation.JsonbNumberFormat packageNumberFormat(Class<?> c) {
        var pkg = c.getPackage();
        if (pkg == null) return null;
        ClassLoader cl = c.getClassLoader();
        loadPackageInfo(pkg, cl == null ? ClassLoader.getSystemClassLoader() : cl);
        return pkg.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
    }

    /** The format of a {@code @JsonbNumberFormat}: its pattern, or the locale's default one, with its locale. */
    private static java.text.NumberFormat numberFormat(jakarta.json.bind.annotation.JsonbNumberFormat ann) {
        String pattern = ann.value();
        java.util.Locale locale = jakarta.json.bind.annotation.JsonbNumberFormat.DEFAULT_LOCALE.equals(ann.locale())
                ? java.util.Locale.ROOT : java.util.Locale.forLanguageTag(ann.locale());
        if ("##default".equals(pattern) || pattern.isEmpty()) {
            var sym = java.text.DecimalFormatSymbols.getInstance(locale);
            normalizeFrenchGroupSeparator(sym);
            return new java.text.DecimalFormat(((java.text.DecimalFormat) java.text.NumberFormat.getInstance(locale)).toPattern(), sym);
        }
        var sym = new java.text.DecimalFormatSymbols(locale);
        normalizeFrenchGroupSeparator(sym);
        return new java.text.DecimalFormat(pattern, sym);
    }

    private static void normalizeFrenchGroupSeparator(java.text.DecimalFormatSymbols sym) {
        char sep = sym.getGroupingSeparator();
        if (sep == '\u202F' || sep == ' ') {
            sym.setGroupingSeparator('\u00A0');
        }
    }

    private static Class<?> boxOfPrim(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        return p;
    }

    private static Object convertNumber(Number n, Class<?> target) {
        if (target == Integer.class) return n.intValue();
        if (target == Long.class) return n.longValue();
        if (target == Double.class) return n.doubleValue();
        if (target == Float.class) return n.floatValue();
        if (target == Short.class) return n.shortValue();
        if (target == Byte.class) return n.byteValue();
        if (target == BigDecimal.class) return new BigDecimal(n.toString());
        if (target == BigInteger.class) return new BigInteger(n.toString());
        return n;
    }

    /**
     * Opens a member that the binding reads or writes (JSON-B maps private and protected members too).
     * When the module system refuses it, the member is left as it is and its use reports the failure.
     */
    private static void makeAccessible(java.lang.reflect.AccessibleObject member) {
        member.trySetAccessible();
    }

    /** Loads the {@code package-info} class of {@code pkg}, so that the annotations of the package can be read. */
    private static void loadPackageInfo(Package pkg, ClassLoader loader) {
        try {
            Class.forName(pkg.getName() + ".package-info", false, loader);
        } catch (ClassNotFoundException | LinkageError _) {
            // No (loadable) package-info: the package carries no annotation.
        }
    }

    /** Finds a field (any visibility) on the class or its parents. */
    private static Field findFieldByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException _) { /* not declared here: look in the superclass */ }
        }
        return null;
    }

    /** Finds a conventional getter for property {@code prop}. */
    private static Method findGetter(Class<?> type, String prop) {
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                    && (m.getName().equals("get" + cap) || m.getName().equals("is" + cap))) {
                return m;
            }
        }
        return null;
    }

    private String methodJsonbName(Method setter, Method getter, String defaultName) {
        var prop = setter.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        if (getter != null) {
            var p2 = getter.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (p2 != null && !p2.value().isEmpty()) return applyNaming(p2.value(), true);
        }
        // Field underlying (spec §4.1.2)
        Field f = findFieldByName(setter.getDeclaringClass(), defaultName);
        if (f != null) {
            var fp = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (fp != null && !fp.value().isEmpty()) return applyNaming(fp.value(), true);
        }
        return applyNaming(defaultName, false);
    }

    private String applyNaming(String name, boolean annotated) {
        if (annotated || propertyNamingStrategy == null) return name;
        return RuntimeBindingRegistry.transformName(name, propertyNamingStrategy);
    }

    /** Polymorphic setter: receives the instance + parser and applies the read value. */
    interface BeanWriter {
        void apply(Object target, JsonParser p);
        BindingReader reader();
        void applyValue(Object target, Object value);
    }

    private record MethodSetter(Method m, BindingReader reader) implements BeanWriter {
        public void apply(Object target, JsonParser p) {
            Object value;
            try { value = reader.read(p); }
            catch (JsonbException e) { throw e; }
            catch (RuntimeException e) { throw new JsonbException("Failed to read property " + m.getName() + ": " + e.getMessage(), e); }
            applyValue(target, value);
        }
        public void applyValue(Object target, Object value) {
            try { m.invoke(target, value); }
            catch (ReflectiveOperationException e) {
                throw new JsonbException("Setter failed: " + m, e);
            }
            catch (IllegalArgumentException e) {
                throw new JsonbException("Setter argument mismatch on " + m
                        + " — got " + (value == null ? "null" : value.getClass().getName())
                        + " for param " + m.getParameterTypes()[0].getName(), e);
            }
        }
    }

    /**
     * Setter via {@link java.lang.invoke.MethodHandle} (publicLookup) — used for
     * public POJOs with public setters. Avoids {@code setAccessible} and
     * {@code opens}, consistent with strict Java Modules.
     */
    private record MhSetter(java.lang.invoke.MethodHandle mh, Method m, BindingReader reader) implements BeanWriter {
        public void apply(Object target, JsonParser p) {
            Object value;
            try { value = reader.read(p); }
            catch (JsonbException e) { throw e; }
            catch (RuntimeException e) { throw new JsonbException("Failed to read property " + m.getName() + ": " + e.getMessage(), e); }
            applyValue(target, value);
        }
        public void applyValue(Object target, Object value) {
            try { mh.invoke(target, value); }
            catch (Throwable t) {
                throw new JsonbException("Setter failed on '" + m.getName() + "': " + t.getMessage(), t);
            }
        }
    }

    private record FieldSetter(Field f, BindingReader reader) implements BeanWriter {
        public void apply(Object target, JsonParser p) {
            Object value;
            try { value = reader.read(p); }
            catch (JsonbException e) { throw e; }
            catch (RuntimeException e) { throw new JsonbException("Failed to read field " + f.getName() + ": " + e.getMessage(), e); }
            applyValue(target, value);
        }
        public void applyValue(Object target, Object value) {
            try { f.set(target, value); }
            catch (IllegalAccessException e) {
                throw new JsonbException("Field set failed: " + f, e);
            }
        }
    }

    private Object readObjectAndApply(JsonParser p, Constructor<?> ctor,
                                      java.lang.invoke.MethodHandle ctorMh, Class<?> type,
                                      Map<String, BeanWriter> writers) {
        if (!enterObject(p)) return null;
        Object inst;
        try {
            // §R-2 — prefer MethodHandle (publicLookup) for public POJOs.
            if (ctorMh != null) inst = ctorMh.invoke();
            else inst = ctor.newInstance();
        } catch (Throwable ex) {
            throw new JsonbException("ctor failed for " + type, ex);
        }
        boolean open = true;
        while (open) {
            open = readMember(p, inst, ctor.getDeclaringClass(), writers);
        }
        return inst;
    }

    /** Reads the next member of a bean into {@code inst}; false once the bean's object is closed. */
    private boolean readMember(JsonParser p, Object inst, Class<?> declaring, Map<String, BeanWriter> writers) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.END_OBJECT) return false;
        String key = keyName(p, e);
        BeanWriter w = writers.get(key);
        if (w == null) {
            skipUnknown(p, key, declaring);
            return true;
        }
        w.apply(inst, p);
        // Escape hatch for user JsonbDeserializers only (TCK pattern:
        // the deserializer consumed up to the parent's END_OBJECT).
        // Internal readers consume exactly their value: checking them
        // too was BUG-20260611-01 — a nested-POJO member's own
        // END_OBJECT aborted the enclosing object read.
        return !(w.reader() instanceof BindingReader.UserDeserializer
                && p.currentEvent() == JsonParser.Event.END_OBJECT);
    }

    /** Skips the value of an unknown member, unless the configuration rejects unknown properties. */
    private void skipUnknown(JsonParser p, String key, Class<?> declaring) {
        if (failOnUnknownProperties) {
            throw new JsonbException("Unknown property: '" + key + "' on " + declaring);
        }
        skipValue(p);
    }

    /** Finds a date reader for a creator parameter via @JsonbDateFormat. */
    private java.util.Optional<BindingReader> dateReaderForParam(java.lang.reflect.Parameter p, Class<?> paramType) {
        if (!RuntimeBindingRegistry.isDateLikeType(paramType)) return java.util.Optional.empty();
        var ann = p.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
        if (ann == null) return java.util.Optional.empty();
        var spec = new RuntimeBindingRegistry.DateFormatSpec(ann.value(),
                jakarta.json.bind.annotation.JsonbDateFormat.DEFAULT_LOCALE.equals(ann.locale()) ? java.util.Locale.getDefault()
                        : java.util.Locale.forLanguageTag(ann.locale()));
        return java.util.Optional.of(makeDateReader(paramType, spec));
    }

    /** Silent @JsonbCreator lookup for polymorphic use (without strict validation). */
    private static java.lang.reflect.Executable findJsonbCreatorPolymorphic(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) return c;
        }
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) return m;
        }
        return null;
    }

    /**
     * Looks for a constructor or static factory annotated with {@code @JsonbCreator}.
     * Returns null if none is found. Spec §4.6 allows at most one creator,
     * and if it is a static factory its return type must match {@code type}.
     */
    private static java.lang.reflect.Executable findJsonbCreator(Class<?> type) {
        java.util.List<java.lang.reflect.Executable> creators = new java.util.ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) creators.add(c);
        }
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) {
                if (!type.isAssignableFrom(m.getReturnType())) {
                    throw new JsonbException("@JsonbCreator factory method " + m
                            + " must return " + type.getName() + " (got " + m.getReturnType().getName() + ")");
                }
                creators.add(m);
            }
        }
        if (creators.size() > 1) {
            throw new JsonbException("Class " + type.getName()
                    + " has multiple @JsonbCreator-annotated executables — at most one is allowed.");
        }
        return creators.isEmpty() ? null : creators.get(0);
    }

    /**
     * Builds a BindingReader that reads JSON, maps the creator parameters
     * via {@code @JsonbProperty} (or the default parameter name), and invokes
     * the constructor or static factory to produce the instance.
     */
    private BindingReader resolveCreator(java.lang.reflect.Executable creator) {
        makeAccessible(creator);
        java.lang.reflect.Parameter[] params = creator.getParameters();
        Class<?>[] paramTypes = creator.getParameterTypes();
        boolean[] required = new boolean[params.length];
        BindingReader[] readers = new BindingReader[params.length];
        Map<String, Integer> indexByName = HashMap.newHashMap(params.length);
        for (int i = 0; i < params.length; i++) {
            final int idx = i;
            final Type genericParamType = creator.getGenericParameterTypes()[i];
            final java.lang.reflect.Parameter param = params[i];
            final Class<?> paramType = paramTypes[i];
            // §4.7 / §4.4 — @JsonbDateFormat / @JsonbTypeAdapter / @JsonbTypeDeserializer
            // sur le param du creator (ex. AnnotationTypeInfoTest.DateConstructor).
            BindingReader paramReader = customAdapterReader(param, null)
                    // Creator parameter : ne PAS avancer le parser — le
                    // the deserializer performs its own next() (see the TCK
                    // InstantiationCustomizationTest.testJsonbDeserializerOnCreatorParameter).
                    .or(() -> customDeserializerReader(param, null, genericParamType, /*advanceParser*/ false))
                    .or(() -> dateReaderForParam(param, paramType))
                    .orElseGet(() -> readerFor(genericParamType));
            readers[idx] = paramReader;
            String name = paramJsonbName(params[i]);
            indexByName.put(name, i);
            var prop = params[i].getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            // §4.6 — by default, creator parameters are OPTIONAL and receive
            // null / 0 / Optional.empty when the key is absent from JSON.
            // If JsonbConfig.CREATOR_PARAMETERS_REQUIRED=true is set, then ALL
            // les params deviennent required, sauf si @JsonbProperty(nillable=true)
            // parameters must be explicitly present.
            required[i] = creatorParametersRequired && !(prop != null && prop.nillable());
        }
        // Discover additional BeanWriters (public setters/fields not covered by the creator)
        // pour testCustomConstructorPlusFields.
        Class<?> declaring = creator.getDeclaringClass();
        Map<String, BeanWriter> extras = discoverExtraWriters(declaring, indexByName.keySet());
        return parser -> readObjectAndInvokeCreator(parser, creator, paramTypes, readers, indexByName, required, extras);
    }

    /**
     * Computes public BeanWriters (setters + fields) for properties that are NOT
     * covered by creator parameters. Used to apply values after construction,
     * remaining after the creator invocation (JSON-B 3.0 §4.6).
     */
    private Map<String, BeanWriter> discoverExtraWriters(Class<?> type, java.util.Set<String> creatorNames) {
        if (type.isInterface() || type.isPrimitive() || type.isArray()) return Map.of();
        Map<String, BeanWriter> out = new java.util.LinkedHashMap<>();
        // Public setters.
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String prop = extraSetterProperty(type, m);
                String name = prop == null ? null : methodJsonbName(m, findGetter(type, prop), prop);
                if (name != null && !creatorNames.contains(name) && !out.containsKey(name)) {
                    makeAccessible(m);
                    out.put(name, new MethodSetter(m, readerFor(m.getGenericParameterTypes()[0])));
                }
            }
        }
        // Public fields not covered.
        for (Field f : type.getFields()) {
            String name = isBindableField(f) ? jsonbName(f) : null;
            if (name != null && !creatorNames.contains(name) && !out.containsKey(name)) {
                makeAccessible(f);
                out.put(name, new FieldSetter(f, readerFor(f.getGenericType())));
            }
        }
        return out;
    }

    /** The property of a public one-argument setter that neither it nor its underlying field makes transient. */
    private static String extraSetterProperty(Class<?> type, Method m) {
        if (!Modifier.isPublic(m.getModifiers()) || m.getParameterCount() != 1
                || m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
            return null;
        }
        String prop = setterProperty(m);
        Field underlying = prop == null ? null : findFieldByName(type, prop);
        boolean transientField = underlying != null
                && underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
        return transientField ? null : prop;
    }

    private static String paramJsonbName(java.lang.reflect.Parameter p) {
        var prop = p.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        return p.getName();
    }

    @SuppressWarnings("unchecked")
    private Object readObjectAndInvokeCreator(JsonParser p, java.lang.reflect.Executable creator,
                                              Class<?>[] paramTypes, BindingReader[] readers,
                                              Map<String, Integer> indexByName,
                                              boolean[] required,
                                              Map<String, BeanWriter> extras) {
        if (!enterObject(p)) return null;
        Object[] args = defaultArguments(paramTypes);
        boolean[] seen = new boolean[paramTypes.length];
        // Buffer for the setters/fields outside the creator parameters.
        java.util.List<Object[]> deferred = new java.util.ArrayList<>();
        JsonParser.Event e;
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            String key = keyName(p, e);
            Integer idx = indexByName.get(key);
            if (idx != null) {
                args[idx] = readers[idx].read(p);
                seen[idx] = true;
            } else if (extras != null && extras.containsKey(key)) {
                BeanWriter w = extras.get(key);
                Object value = w.reader().read(p);
                deferred.add(new Object[] { w, value });
            } else {
                skipUnknown(p, key, creator.getDeclaringClass());
            }
        }
        checkRequiredParameters(creator, required, seen);
        Object inst = invokeCreator(creator, args);
        // Apply additional setters/fields.
        for (Object[] pair : deferred) {
            ((BeanWriter) pair[0]).applyValue(inst, pair[1]);
        }
        return inst;
    }

    /** Validation: any required parameter absent → JsonbException. */
    private static void checkRequiredParameters(java.lang.reflect.Executable creator, boolean[] required, boolean[] seen) {
        if (required == null) return;
        for (int i = 0; i < seen.length; i++) {
            if (required[i] && !seen[i]) {
                String paramName = creator.getParameters()[i].getName();
                throw new JsonbException("Missing required @JsonbCreator parameter '" + paramName
                        + "' for " + creator.getDeclaringClass().getName());
            }
        }
    }

    private static Object invokeCreator(java.lang.reflect.Executable creator, Object[] args) {
        try {
            if (creator instanceof Constructor<?> c) return c.newInstance(args);
            return ((java.lang.reflect.Method) creator).invoke(null, args);
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to invoke @JsonbCreator: " + ex.getMessage(), ex);
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == java.util.Optional.class) return java.util.Optional.empty();
        if (type == java.util.OptionalInt.class) return java.util.OptionalInt.empty();
        if (type == java.util.OptionalLong.class) return java.util.OptionalLong.empty();
        if (type == java.util.OptionalDouble.class) return java.util.OptionalDouble.empty();
        if (!type.isPrimitive()) return null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == boolean.class) return false;
        return null;
    }

    /** Skips the current value (may be a nested object or array). */
    private static void skipValue(JsonParser p) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.START_OBJECT) p.skipObject();
        else if (e == JsonParser.Event.START_ARRAY) p.skipArray();
        // otherwise scalar → consumed by next()
    }

    // ===== Parameterized =====

    private BindingReader parameterizedReader(java.lang.reflect.ParameterizedType p) {
        Class<?> raw = (Class<?>) p.getRawType();
        Type[] args = p.getActualTypeArguments();
        if (java.util.Map.class.isAssignableFrom(raw)) {
            BindingReader vr = args.length >= 2 ? readerFor(args[1]) : (BindingReader) this::dynamicValue;
            return mapReader(vr, raw);
        }
        if (java.util.Collection.class.isAssignableFrom(raw)) {
            BindingReader er = args.length >= 1 ? readerFor(args[0]) : (BindingReader) this::dynamicValue;
            return collectionReader(er, raw);
        }
        if (raw == Optional.class) {
            BindingReader inner = args.length >= 1 ? readerFor(args[0]) : (BindingReader) this::dynamicValue;
            return optionalReader(inner);
        }
        return classCache.get(raw);
    }

    private BindingReader mapReader(BindingReader valueReader, Class<?> raw) {
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object for Map, got " + e);
            Map<String, Object> out = newMapFor(raw);
            while ((e = parser.next()) != JsonParser.Event.END_OBJECT) {
                if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
                String key = parser.getString();
                out.put(key, valueReader.read(parser));
            }
            return out;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> newMapFor(Class<?> raw) {
        if (java.util.SortedMap.class.isAssignableFrom(raw) || java.util.NavigableMap.class.isAssignableFrom(raw)
                || raw == java.util.TreeMap.class) {
            return new java.util.TreeMap<>();
        }
        if (raw == java.util.concurrent.ConcurrentHashMap.class) return new java.util.concurrent.ConcurrentHashMap<>();
        return new LinkedHashMap<>();
    }

    private BindingReader collectionReader(BindingReader elemReader, Class<?> raw) {
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
            java.util.Collection<Object> out = newCollectionFor(raw);
            while (true) {
                JsonParser.Event tok = parser.next();
                if (tok == JsonParser.Event.END_ARRAY) return out;
                JsonParser primed = new PrimedParser(tok, parser);
                out.add(elemReader.read(primed));
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Collection<Object> newCollectionFor(Class<?> raw) {
        // Cas exacts d'abord (avant les check d'interface qui pourraient matcher).
        if (raw == java.util.LinkedList.class) return new java.util.LinkedList<>();
        if (raw == java.util.ArrayDeque.class) return new java.util.ArrayDeque<>();
        if (raw == java.util.PriorityQueue.class) return new java.util.PriorityQueue<>();
        if (raw == java.util.concurrent.PriorityBlockingQueue.class) return new java.util.concurrent.PriorityBlockingQueue<>();
        if (raw == java.util.TreeSet.class) return new java.util.TreeSet<>();
        if (raw == java.util.HashSet.class) return new java.util.HashSet<>();
        if (raw == java.util.LinkedHashSet.class) return new java.util.LinkedHashSet<>();
        // Interfaces / supertypes ensuite.
        if (java.util.SortedSet.class.isAssignableFrom(raw) || java.util.NavigableSet.class.isAssignableFrom(raw)) {
            return new java.util.TreeSet<>();
        }
        if (java.util.Set.class.isAssignableFrom(raw)) return new java.util.LinkedHashSet<>();
        if (java.util.Deque.class.isAssignableFrom(raw)) return new java.util.ArrayDeque<>();
        if (java.util.Queue.class.isAssignableFrom(raw)) return new java.util.LinkedList<>();
        return new ArrayList<>();
    }

    // ===== Optional / Builtins / arrays =====

    private BindingReader optionalReader(BindingReader inner) {
        return parser -> {
            // Read the value; null → Optional.empty
            Object v = inner.read(parser);
            return v == null ? Optional.empty() : Optional.of(v);
        };
    }

    private BindingReader arrayReader(Class<?> componentType) {
        if (componentType == byte.class) return byteArrayReader();
        BindingReader primitives = primitiveArrayReader(componentType);
        if (primitives != null) return primitives;
        BindingReader elem = readerFor(componentType);
        return parser -> {
            if (!(collectArray(parser, elem) instanceof List<?> tmp)) return null;
            Object out = java.lang.reflect.Array.newInstance(componentType, tmp.size());
            for (int i = 0; i < tmp.size(); i++) java.lang.reflect.Array.set(out, i, tmp.get(i));
            return out;
        };
    }

    /** The reader of an array of a primitive type other than {@code byte}, null for any other component type. */
    private static BindingReader primitiveArrayReader(Class<?> componentType) {
        if (componentType == int.class) {
            return parser -> toIntArray(collectArray(parser, q -> { q.next(); return q.getInt(); }));
        }
        if (componentType == long.class) {
            return parser -> toLongArray(collectArray(parser, q -> { q.next(); return q.getLong(); }));
        }
        if (componentType == double.class) {
            return parser -> toDoubleArray(collectArray(parser, q -> { q.next(); return q.getBigDecimal().doubleValue(); }));
        }
        if (componentType == boolean.class) {
            return parser -> toBooleanArray(collectArray(parser, q -> q.next() == JsonParser.Event.VALUE_TRUE));
        }
        if (componentType == short.class) {
            return parser -> toShortArray(collectArray(parser, q -> { q.next(); return (short) q.getInt(); }));
        }
        if (componentType == float.class) {
            return parser -> toFloatArray(collectArray(parser, q -> { q.next(); return q.getBigDecimal().floatValue(); }));
        }
        if (componentType == char.class) {
            return parser -> toCharArray(collectArray(parser, RuntimeReadRegistry::readArrayChar));
        }
        return null;
    }

    private static Object readArrayChar(JsonParser q) {
        q.next();
        String s = q.getString();
        return s.isEmpty() ? '\0' : s.charAt(0);
    }

    private static Object toIntArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        int[] out = new int[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Integer) tmp.get(i);
        return out;
    }

    private static Object toLongArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        long[] out = new long[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Long) tmp.get(i);
        return out;
    }

    private static Object toDoubleArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        double[] out = new double[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Double) tmp.get(i);
        return out;
    }

    private static Object toBooleanArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        boolean[] out = new boolean[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Boolean) tmp.get(i);
        return out;
    }

    private static Object toShortArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        short[] out = new short[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Short) tmp.get(i);
        return out;
    }

    private static Object toFloatArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        float[] out = new float[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Float) tmp.get(i);
        return out;
    }

    private static Object toCharArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        char[] out = new char[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Character) tmp.get(i);
        return out;
    }

    private static Object toByteArray(Object collected) {
        if (!(collected instanceof List<?> tmp)) return null;
        byte[] out = new byte[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = (Byte) tmp.get(i);
        return out;
    }

    /**
     * JSON-B 3.0 §3.3.1 — byte[] deserialization:
     * <ul>
     *   <li>BYTE (default): reads an integer array</li>
     *   <li>BASE_64 : lit une string base64 standard</li>
     *   <li>BASE_64_URL : lit une string base64 URL-safe</li>
     * </ul>
     * A fallback is also accepted: if the strategy is BYTE but a string is read,
     * try base64 decoding (and vice versa) to stay tolerant.
     */
    private BindingReader byteArrayReader() {
        String s = binaryDataStrategy;
        if ("BASE_64".equals(s)) return base64Reader(java.util.Base64.getDecoder(), s);
        if ("BASE_64_URL".equals(s)) return base64Reader(java.util.Base64.getUrlDecoder(), s);
        // BYTE (default)
        return parser -> toByteArray(collectArray(parser, q -> { q.next(); return (byte) q.getInt(); }));
    }

    /** Reads a {@code byte[]} from a base64 string, decoded with {@code decoder} ({@code strategy} names it in errors). */
    private static BindingReader base64Reader(java.util.Base64.Decoder decoder, String strategy) {
        return parser -> {
            JsonParser.Event ev = parser.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            if (ev != JsonParser.Event.VALUE_STRING) {
                throw new JsonbException("Expected VALUE_STRING for " + strategy + " byte[], got " + ev);
            }
            return decoder.decode(parser.getString());
        };
    }

    /**
     * Reads an array and applies {@code elem.read} to each element. Handles the case
     * where the leading event is consumed by checking {@code hasNext} before retrying.
     */
    private static Object collectArray(JsonParser p, BindingReader elem) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
        var out = new ArrayList<Object>();
        // Wrapper "lookahead" : on lit next, si END_ARRAY on sort, sinon on rebranche un
        // mini-parser that restores that event to the element reader.
        while (true) {
            JsonParser.Event tok = p.next();
            if (tok == JsonParser.Event.END_ARRAY) return out;
            // Replay: create a "primed" parser that returns tok then delegates.
            JsonParser primed = new PrimedParser(tok, p);
            out.add(elem.read(primed));
        }
    }

    /**
     * Parser that re-emits an event consumed upstream, then delegates the rest to the underlying parser.
     * Permet aux readers de toujours commencer par {@code parser.next()}.
     */
    private static final class PrimedParser implements JsonParser {
        private JsonParser.Event primed;
        private final JsonParser delegate;
        PrimedParser(JsonParser.Event primed, JsonParser delegate) { this.primed = primed; this.delegate = delegate; }
        @Override public boolean hasNext() { return primed != null || delegate.hasNext(); }
        @Override public Event next() {
            if (primed != null) { var e = primed; primed = null; return e; }
            return delegate.next();
        }
        @Override public Event currentEvent() {
            return primed != null ? null : delegate.currentEvent();
        }
        @Override public String getString() { return delegate.getString(); }
        @Override public boolean isIntegralNumber() { return delegate.isIntegralNumber(); }
        @Override public int getInt() { return delegate.getInt(); }
        @Override public long getLong() { return delegate.getLong(); }
        @Override public java.math.BigDecimal getBigDecimal() { return delegate.getBigDecimal(); }
        @Override public jakarta.json.stream.JsonLocation getLocation() { return delegate.getLocation(); }
        @Override public jakarta.json.JsonValue getValue() { return delegate.getValue(); }
        @Override public jakarta.json.JsonObject getObject() { return delegate.getObject(); }
        @Override public jakarta.json.JsonArray getArray() { return delegate.getArray(); }
        @Override public java.util.stream.Stream<jakarta.json.JsonValue> getArrayStream() { return delegate.getArrayStream(); }
        @Override public java.util.stream.Stream<Map.Entry<String, jakarta.json.JsonValue>> getObjectStream() { return delegate.getObjectStream(); }
        @Override public java.util.stream.Stream<jakarta.json.JsonValue> getValueStream() { return delegate.getValueStream(); }
        @Override public void skipArray() { delegate.skipArray(); }
        @Override public void skipObject() { delegate.skipObject(); }
        @Override public void close() { delegate.close(); }
    }

    private BindingReader enumReader(Class<?> type) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<? extends Enum> e = (Class<? extends Enum>) type;
        return parser -> {
            JsonParser.Event ev = parser.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            if (ev != JsonParser.Event.VALUE_STRING) throw new JsonbException("Expected string for enum, got " + ev);
            @SuppressWarnings("unchecked")
            Object out = Enum.valueOf(e, parser.getString());
            return out;
        };
    }

    /** Reads the current value as an Object / dynamically determined. */
    private Object dynamicValue(JsonParser p) {
        JsonParser.Event e = p.next();
        return switch (e) {
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER -> {
                BigDecimal bd = p.getBigDecimal();
                if (p.isIntegralNumber()) {
                    // An int when it fits (bit length below 32), otherwise a BigInteger of any size.
                    BigInteger bi = bd.toBigIntegerExact();
                    yield bi.bitLength() < Integer.SIZE ? Integer.valueOf(bi.intValue()) : bi;
                }
                yield bd;
            }
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            case START_OBJECT -> readDynamicMap(p);
            case START_ARRAY -> readDynamicArray(p);
            default -> throw new JsonbException("Unexpected event: " + e);
        };
    }

    private Map<String, Object> readDynamicMap(JsonParser p) {
        var out = new LinkedHashMap<String, Object>();
        while (true) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_OBJECT) return out;
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME");
            String key = p.getString();
            // recursion on dynamic: dynamicValue advances next() itself
            out.put(key, dynamicValue(p));
        }
    }

    private List<Object> readDynamicArray(JsonParser p) {
        var out = new ArrayList<Object>();
        while (true) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_ARRAY) return out;
            JsonParser primed = new PrimedParser(e, p);
            out.add(dynamicValue(primed));
        }
    }

    /**
     * Polymorphic reader: reads the discriminator key (first in the JSON,
     * MVP M4.5), finds the concrete class associated with the alias, then reads the remaining members
     * restants directement dans le record/POJO concret via une variante de
     * readObjectAndConstruct, which assumes START_OBJECT has already been consumed.
     *
     * <p>Note: Champollion always writes the discriminator key first.
     * To interoperate with other implementations that place the key elsewhere, it
     * would need to buffer the whole object — deferred.</p>
     */
    private BindingReader polymorphicReader(jakarta.json.bind.annotation.JsonbTypeInfo info) {
        // Validation : alias-not-subtype / key-collision (TypeInfoExceptionsTest).
        for (var sub : info.value()) {
            RuntimeBindingRegistry.validateTypeInfo(sub.type(), info);
        }
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) {
                throw new JsonbException("Expected START_OBJECT for polymorphic value, got " + e);
            }
            return readMembersOnly(parser, concreteType(parser, info));
        };
    }

    /**
     * Cascade: starting from the first discriminator key, follows the chain of {@code @JsonbTypeInfo} from
     * ancestor to child. Each step reads the current discriminator key and maps its alias to a subtype; when
     * that subtype itself carries {@code @JsonbTypeInfo}, the next discriminator is read.
     */
    private static Class<?> concreteType(JsonParser parser, jakarta.json.bind.annotation.JsonbTypeInfo info) {
        var currentInfo = info;
        Class<?> concrete;
        do {
            concrete = subtypeOf(currentInfo, readDiscriminator(parser, currentInfo));
            currentInfo = concrete.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
        } while (currentInfo != null);
        return concrete;
    }

    /** Reads the discriminator member of {@code info}, which must come next, and returns its alias. */
    private static String readDiscriminator(JsonParser parser, jakarta.json.bind.annotation.JsonbTypeInfo info) {
        JsonParser.Event e = parser.next();
        if (e != JsonParser.Event.KEY_NAME) {
            throw new JsonbException("Expected KEY_NAME (discriminator '" + info.key() + "'), got " + e);
        }
        String key = parser.getString();
        if (!key.equals(info.key())) {
            throw new JsonbException("Expected discriminator '" + info.key()
                    + "' as next member, got '" + key + "'");
        }
        e = parser.next();
        if (e != JsonParser.Event.VALUE_STRING) {
            throw new JsonbException("Expected VALUE_STRING for discriminator, got " + e);
        }
        return parser.getString();
    }

    private static Class<?> subtypeOf(jakarta.json.bind.annotation.JsonbTypeInfo info, String alias) {
        for (var sub : info.value()) {
            if (sub.alias().equals(alias)) return sub.type();
        }
        throw new JsonbException("Unknown @JsonbSubtype alias: " + alias);
    }

    /**
     * Lit les membres restants d'un objet (sans START_OBJECT initial) et construit
     * an instance of the concrete type. Used for the second phase of reading
     * polymorphe.
     */
    private Object readMembersOnly(JsonParser parser, Class<?> concrete) {
        if (concrete.isRecord()) return readRecordMembers(parser, concrete);
        // Non-record POJO: if @JsonbCreator is present, delegate to the creator-based reader
        // (members already advanced past the discriminator are absorbed by rereading
        // the object through a PrimedParser that replays START_OBJECT). Otherwise the no-arg ctor.
        var creator = findJsonbCreatorPolymorphic(concrete);
        if (creator != null) {
            // Build a creator-based BindingReader then call it with a primed parser
            // that re-injects START_OBJECT at the beginning.
            BindingReader r = resolveCreator(creator);
            JsonParser primed = new PrimedParser(JsonParser.Event.START_OBJECT, parser);
            return r.read(primed);
        }
        Object inst = newSubtypeInstance(concrete);
        Map<String, BeanWriter> writers = subtypeWriters(concrete);
        JsonParser.Event ev;
        while ((ev = parser.next()) != JsonParser.Event.END_OBJECT) {
            BeanWriter w = writers.get(keyName(parser, ev));
            if (w == null) skipValue(parser);
            else w.apply(inst, parser);
        }
        return inst;
    }

    private Object readRecordMembers(JsonParser parser, Class<?> concrete) {
        RecordComponent[] comps = concrete.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[comps.length];
        BindingReader[] readers = new BindingReader[comps.length];
        Map<String, Integer> indexByName = componentReaders(comps, paramTypes, readers);
        Constructor<?> ctor;
        try { ctor = concrete.getDeclaredConstructor(paramTypes); }
        catch (NoSuchMethodException e) {
            throw new JsonbException("Canonical record constructor not found for " + concrete, e);
        }
        makeAccessible(ctor);
        Object[] args = readArguments(parser, paramTypes, readers, indexByName);
        try { return ctor.newInstance(args); }
        catch (ReflectiveOperationException ex) {
            throw new JsonbException("ctor failed for " + concrete, ex);
        }
    }

    /** A new instance of a polymorphic subtype through its public or protected no-arg constructor. */
    private static Object newSubtypeInstance(Class<?> concrete) {
        Constructor<?> ctor;
        try { ctor = concrete.getDeclaredConstructor(); }
        catch (NoSuchMethodException nse) {
            throw new JsonbException("No no-arg constructor for " + concrete, nse);
        }
        int cMods = ctor.getModifiers();
        if (!Modifier.isPublic(cMods) && !Modifier.isProtected(cMods)) {
            throw new JsonbException("No accessible no-arg constructor for " + concrete);
        }
        makeAccessible(ctor);
        try { return ctor.newInstance(); }
        catch (ReflectiveOperationException ex) { throw new JsonbException("ctor failed for " + concrete, ex); }
    }

    /** The public one-argument setters of a polymorphic subtype, then its public fields not covered by a setter. */
    private Map<String, BeanWriter> subtypeWriters(Class<?> concrete) {
        Map<String, BeanWriter> writers = new HashMap<>();
        for (Method sm : concrete.getMethods()) {
            String prop = sm.getParameterCount() == 1 ? setterProperty(sm) : null;
            if (prop != null) {
                makeAccessible(sm);
                writers.put(prop, new MethodSetter(sm, readerFor(sm.getGenericParameterTypes()[0])));
            }
        }
        // Public fields not covered by a setter (case Dog.isDog).
        for (Field f : concrete.getFields()) {
            if (isBindableField(f) && !writers.containsKey(f.getName())) {
                makeAccessible(f);
                writers.put(f.getName(), new FieldSetter(f, readerFor(f.getGenericType())));
            }
        }
        return writers;
    }

    /**
     * Global reader for {@code java.time.*} types based on {@code JSONB_DATE_FORMAT}.
     * Returns empty if there is no global pattern or if {@code raw} is not a supported java.time type.
     */
    private java.util.Optional<BindingReader> globalDateReader(Class<?> raw) {
        if (defaultDateFormat == null) return java.util.Optional.empty();
        if (raw != java.time.LocalDate.class
                && raw != java.time.LocalDateTime.class
                && raw != java.time.OffsetDateTime.class
                && raw != java.time.ZonedDateTime.class
                && raw != java.time.Instant.class) {
            return java.util.Optional.empty();
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(defaultDateFormat);
        return java.util.Optional.of(parser -> {
            JsonParser.Event ev = parser.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            String s = parser.getString();
            if (raw == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
            if (raw == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
            if (raw == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
            if (raw == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
            return java.time.Instant.from(fmt.parse(s));
        });
    }

    /**
     * Si le composant a {@code @JsonbTypeAdapter(class)}, retourne un reader qui :
     * 1. instancie l'adapter (no-arg ctor),
     * 2. delegates reading to the reader of the {@code Adapted} type,
     * 3. invoque {@code adaptFromJson(adapted)} pour reconstruire l'Original.
     */
    private java.util.Optional<BindingReader> customAdapterReader(RecordComponent c) {
        var direct = c.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        var fromAccessor = direct == null
                ? c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class)
                : null;
        return adapterReaderFromAnnotation(direct != null ? direct : fromAccessor);
    }

    /** @JsonbTypeAdapter sur un member (setter, field) avec repli sur underlying field. */
    java.util.Optional<BindingReader> customAdapterReader(java.lang.reflect.AnnotatedElement member,
                                                          java.lang.reflect.Field underlying) {
        var ann = member == null ? null : member.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        if (ann == null && underlying != null) {
            ann = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        }
        return adapterReaderFromAnnotation(ann);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private java.util.Optional<BindingReader> adapterReaderFromAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter ann) {
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass = ann.value();
        jakarta.json.bind.adapter.JsonbAdapter adapter;
        try {
            // §5 — CDI resolution if a container is available, otherwise newInstance.
            adapter = CdiResolver.resolve(adapterClass);
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbAdapter " + adapterClass, e);
        }
        java.lang.reflect.Type adaptedGeneric = RuntimeBindingRegistry.findAdaptedGenericType(adapterClass);
        BindingReader inner = readerForGeneric(adaptedGeneric);
        return java.util.Optional.of(parser -> {
            Object adapted = inner.read(parser);
            if (adapted == null) return null;
            try { return adapter.adaptFromJson(adapted); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on fromJson: " + ex.getMessage(), ex); }
        });
    }


    /**
     * Si le composant a {@code @JsonbDateFormat}, retourne un reader custom.
     * Couvre LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime, Instant.
     */
    private static java.util.Optional<BindingReader> customDateReader(RecordComponent c) {
        var direct = c.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
        var fromAccessor = direct == null
                ? c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class)
                : null;
        var ann = direct != null ? direct : fromAccessor;
        if (ann == null) return java.util.Optional.empty();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(ann.value());
        Class<?> raw = c.getType();
        return java.util.Optional.of(parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.VALUE_STRING) {
                throw new JsonbException("@JsonbDateFormat expects a JSON string, got " + e);
            }
            return parseFormattedTemporal(raw, parser.getString(), fmt);
        });
    }

    /** A {@code @JsonbDateFormat} value of a record component. */
    private static Object parseFormattedTemporal(Class<?> raw, String s, java.time.format.DateTimeFormatter fmt) {
        if (raw == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
        if (raw == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
        if (raw == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
        if (raw == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
        // Instant has no parse(String, DateTimeFormatter): Instant.from reads the parsed fields.
        if (raw == java.time.Instant.class) return java.time.Instant.from(fmt.parse(s));
        throw new JsonbException("@JsonbDateFormat unsupported type: " + raw);
    }

    // ===== Customization helpers (M4.4) =====

    private static boolean isJsonbTransient(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) return true;
        return c.getAccessor().isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    private String jsonbName(RecordComponent c) {
        var prop = c.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        var accessorProp = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (accessorProp != null && !accessorProp.value().isEmpty()) return applyNaming(accessorProp.value(), true);
        return applyNaming(c.getName(), false);
    }

    private String jsonbName(Field f) {
        var prop = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        return applyNaming(f.getName(), false);
    }

    // ===== Builtins =====

    /** Parse une string en {@link Instant} en essayant plusieurs formats ISO. */
    private static Instant parseAsInstant(String s) {
        try { return ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant(); } catch (Exception _) { /* not this format: try the next one */ }
        try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); } catch (Exception _) { /* not this format: try the next one */ }
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.of("UTC")).toInstant(); } catch (Exception _) { /* not this format: try the next one */ }
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant(); } catch (Exception _) { /* not this format: try the next one */ }
        return Instant.parse(s);
    }

    /** Parse une string en {@link ZonedDateTime} en essayant plusieurs formats ISO. */
    private static ZonedDateTime parseAsZonedDateTime(String s) {
        try { return ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME); } catch (Exception _) { /* not this format: try the next one */ }
        try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime(); } catch (Exception _) { /* not this format: try the next one */ }
        try {
            // Date with an offset only, as written for a calendar at midnight: "1970-01-01+01:00", "1970-01-01Z"
            var dateWithOffset = DateTimeFormatter.ISO_OFFSET_DATE.parse(s);
            return LocalDate.from(dateWithOffset).atStartOfDay(java.time.ZoneOffset.from(dateWithOffset));
        } catch (Exception _) { /* not this format: try the next one */ }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(java.time.ZoneId.of("UTC"));
        } catch (Exception _) { /* not this format: try the next one */ }
        return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.of("UTC"));
    }

    /** Readers of the types that JSON-B maps natively (JSON-B 3.0 §3.3 to §3.5), one family per method. */
    private static final class Builtins {

        /** Rejects deprecated 3-letter timezone IDs (JDK: ZoneId.SHORT_IDS). */
        private static final java.util.Set<String> DEPRECATED_TZ_IDS = java.util.Set.of(
                "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST",
                "CTT", "EAT", "ECT", "EST", "HST", "IET", "IST", "JST", "MIT", "MST",
                "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST");

        private Builtins() {
        }

        static BindingReader lookup(Class<?> type) {
            BindingReader reader = jsonpReader(type);
            if (reader == null) reader = numberReader(type);
            if (reader == null) reader = textReader(type);
            if (reader == null) reader = dateTimeReader(type);
            if (reader == null) reader = otherTimeReader(type);
            return reader;
        }

        /** JSON-P types. */
        private static BindingReader jsonpReader(Class<?> type) {
            if (type == jakarta.json.JsonObject.class) return Builtins::readJsonObject;
            if (type == jakarta.json.JsonArray.class) return Builtins::readJsonArray;
            if (type == jakarta.json.JsonString.class || type == jakarta.json.JsonNumber.class) return Builtins::readJsonScalar;
            if (type == jakarta.json.JsonStructure.class || type == jakarta.json.JsonValue.class) return Builtins::readJsonValue;
            return null;
        }

        private static BindingReader numberReader(Class<?> type) {
            if (type == Integer.class || type == int.class) return Builtins::readInt;
            if (type == Long.class || type == long.class) return Builtins::readLong;
            if (type == Double.class || type == double.class) return Builtins::readDouble;
            if (type == Float.class || type == float.class) return Builtins::readFloat;
            if (type == Short.class || type == short.class) return Builtins::readShort;
            if (type == Byte.class || type == byte.class) return Builtins::readByte;
            if (type == BigDecimal.class) return Builtins::readBigDecimal;
            if (type == BigInteger.class) return Builtins::readBigInteger;
            return null;
        }

        private static BindingReader textReader(Class<?> type) {
            if (type == String.class) return Builtins::readString;
            if (type == Boolean.class || type == boolean.class) return Builtins::readBoolean;
            if (type == Character.class || type == char.class) return Builtins::readChar;
            if (type == UUID.class) return Builtins::readUuid;
            if (type == java.net.URI.class) return Builtins::readUri;
            if (type == java.net.URL.class) return Builtins::readUrl;
            if (type == java.util.OptionalInt.class) return Builtins::readOptionalInt;
            if (type == java.util.OptionalLong.class) return Builtins::readOptionalLong;
            if (type == java.util.OptionalDouble.class) return Builtins::readOptionalDouble;
            return null;
        }

        private static BindingReader dateTimeReader(Class<?> type) {
            if (type == Instant.class) return Builtins::readInstant;
            if (type == LocalDate.class) return Builtins::readLocalDate;
            if (type == LocalDateTime.class) return Builtins::readLocalDateTime;
            if (type == OffsetDateTime.class) return Builtins::readOffsetDateTime;
            if (type == ZonedDateTime.class) return Builtins::readZonedDateTime;
            if (type == java.time.LocalTime.class) return Builtins::readLocalTime;
            if (type == java.time.OffsetTime.class) return Builtins::readOffsetTime;
            if (type == java.util.Date.class) return Builtins::readDate;
            if (java.util.GregorianCalendar.class.isAssignableFrom(type) || type == java.util.Calendar.class) {
                return Builtins::readCalendar;
            }
            return null;
        }

        private static BindingReader otherTimeReader(Class<?> type) {
            if (type == java.time.Duration.class) return Builtins::readDuration;
            if (type == java.time.Period.class) return Builtins::readPeriod;
            if (type == java.time.MonthDay.class) return Builtins::readMonthDay;
            if (type == java.time.YearMonth.class) return Builtins::readYearMonth;
            if (type == java.time.Year.class) return Builtins::readYear;
            if (type == java.time.ZoneOffset.class) return Builtins::readZoneOffset;
            if (java.time.ZoneId.class.isAssignableFrom(type)) return Builtins::readZoneId;
            if (type == java.util.SimpleTimeZone.class) return Builtins::readSimpleTimeZone;
            if (java.util.TimeZone.class.isAssignableFrom(type)) return Builtins::readTimeZone;
            return null;
        }

        /** Consumes the value's first event: true for a JSON {@code null}. */
        private static boolean nextIsNull(JsonParser p) {
            return p.next() == JsonParser.Event.VALUE_NULL;
        }

        private static Object readJsonObject(JsonParser p) {
            var e = p.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected JSON object, got " + e);
            return p.getObject();
        }

        private static Object readJsonArray(JsonParser p) {
            var e = p.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected JSON array, got " + e);
            return p.getArray();
        }

        private static Object readJsonScalar(JsonParser p) {
            return nextIsNull(p) ? null : p.getValue();
        }

        private static Object readJsonValue(JsonParser p) {
            return nextIsNull(p) ? jakarta.json.JsonValue.NULL : p.getValue();
        }

        private static Object readString(JsonParser p) {
            return nextIsNull(p) ? null : p.getString();
        }

        private static Object readInt(JsonParser p) {
            return nextIsNull(p) ? null : p.getInt();
        }

        private static Object readLong(JsonParser p) {
            return nextIsNull(p) ? null : p.getLong();
        }

        private static Object readDouble(JsonParser p) {
            return nextIsNull(p) ? null : p.getBigDecimal().doubleValue();
        }

        private static Object readFloat(JsonParser p) {
            return nextIsNull(p) ? null : p.getBigDecimal().floatValue();
        }

        private static Object readShort(JsonParser p) {
            return nextIsNull(p) ? null : (short) p.getInt();
        }

        private static Object readByte(JsonParser p) {
            return nextIsNull(p) ? null : (byte) p.getInt();
        }

        private static Object readBigDecimal(JsonParser p) {
            return nextIsNull(p) ? null : p.getBigDecimal();
        }

        private static Object readBigInteger(JsonParser p) {
            return nextIsNull(p) ? null : p.getBigDecimal().toBigIntegerExact();
        }

        private static Object readBoolean(JsonParser p) {
            var e = p.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e == JsonParser.Event.VALUE_TRUE) return Boolean.TRUE;
            if (e == JsonParser.Event.VALUE_FALSE) return Boolean.FALSE;
            throw new JsonbException("Expected boolean, got " + e);
        }

        private static Object readChar(JsonParser p) {
            if (nextIsNull(p)) return null;
            String s = p.getString();
            return s.isEmpty() ? '\0' : s.charAt(0);
        }

        private static Object readUuid(JsonParser p) {
            return nextIsNull(p) ? null : UUID.fromString(p.getString());
        }

        private static Object readUri(JsonParser p) {
            return nextIsNull(p) ? null : java.net.URI.create(p.getString());
        }

        private static Object readUrl(JsonParser p) {
            if (nextIsNull(p)) return null;
            try { return java.net.URI.create(p.getString()).toURL(); }
            catch (Exception ex) { throw new JsonbException("Invalid URL: " + ex.getMessage(), ex); }
        }

        private static Object readOptionalInt(JsonParser p) {
            return nextIsNull(p) ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(p.getInt());
        }

        private static Object readOptionalLong(JsonParser p) {
            return nextIsNull(p) ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(p.getLong());
        }

        private static Object readOptionalDouble(JsonParser p) {
            return nextIsNull(p) ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of(p.getBigDecimal().doubleValue());
        }

        private static Object readInstant(JsonParser p) {
            return nextIsNull(p) ? null : Instant.parse(p.getString());
        }

        private static Object readLocalDate(JsonParser p) {
            return nextIsNull(p) ? null : LocalDate.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE);
        }

        private static Object readLocalDateTime(JsonParser p) {
            return nextIsNull(p) ? null : LocalDateTime.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        private static Object readOffsetDateTime(JsonParser p) {
            return nextIsNull(p) ? null : OffsetDateTime.parse(p.getString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        private static Object readZonedDateTime(JsonParser p) {
            return nextIsNull(p) ? null : ZonedDateTime.parse(p.getString(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        }

        private static Object readLocalTime(JsonParser p) {
            return nextIsNull(p) ? null : java.time.LocalTime.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_TIME);
        }

        private static Object readOffsetTime(JsonParser p) {
            return nextIsNull(p) ? null : java.time.OffsetTime.parse(p.getString(), DateTimeFormatter.ISO_OFFSET_TIME);
        }

        private static Object readDate(JsonParser p) {
            return nextIsNull(p) ? null : java.util.Date.from(parseAsInstant(p.getString()));
        }

        private static Object readCalendar(JsonParser p) {
            return nextIsNull(p) ? null : java.util.GregorianCalendar.from(parseAsZonedDateTime(p.getString()));
        }

        private static Object readDuration(JsonParser p) {
            return nextIsNull(p) ? null : java.time.Duration.parse(p.getString());
        }

        private static Object readPeriod(JsonParser p) {
            return nextIsNull(p) ? null : java.time.Period.parse(p.getString());
        }

        private static Object readMonthDay(JsonParser p) {
            return nextIsNull(p) ? null : java.time.MonthDay.parse(p.getString());
        }

        private static Object readYearMonth(JsonParser p) {
            return nextIsNull(p) ? null : java.time.YearMonth.parse(p.getString());
        }

        private static Object readYear(JsonParser p) {
            return nextIsNull(p) ? null : java.time.Year.parse(p.getString());
        }

        private static Object readZoneOffset(JsonParser p) {
            return nextIsNull(p) ? null : java.time.ZoneOffset.of(p.getString());
        }

        private static Object readZoneId(JsonParser p) {
            return nextIsNull(p) ? null : java.time.ZoneId.of(p.getString());
        }

        private static Object readSimpleTimeZone(JsonParser p) {
            if (nextIsNull(p)) return null;
            String id = p.getString();
            rejectDeprecatedTimezoneId(id);
            var tz = java.util.TimeZone.getTimeZone(id);
            return new java.util.SimpleTimeZone(tz.getRawOffset(), id);
        }

        private static Object readTimeZone(JsonParser p) {
            if (nextIsNull(p)) return null;
            String id = p.getString();
            rejectDeprecatedTimezoneId(id);
            return java.util.TimeZone.getTimeZone(id);
        }

        private static void rejectDeprecatedTimezoneId(String id) {
            if (DEPRECATED_TZ_IDS.contains(id)) {
                throw new JsonbException("Deprecated three-letter time zone ID: " + id);
            }
        }
    }
}
