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
 * Registre des {@link BindingWriter} résolus à la première rencontre d'un type.
 *
 * <p>Cache : {@link ClassValue} pour amortir le coût de la réflexion. Pour les types
 * paramétrés (collections génériques, M4.3), un {@code ConcurrentHashMap<Type, ...>}
 * sera ajouté en complément.</p>
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
        // Adapter ou Serializer global enregistré pour ce type : court-circuit.
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
                // Interface générique (ex. TypeContainer<T>) : différer au runtime via la classe concrète.
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
     * Cherche un adapter global enregistré via {@code JsonbConfig.withAdapters} dont
     * le type {@code Original} est assignable depuis {@code raw}. Renvoie un writer
     * qui invoque {@code adaptToJson} puis délègue au writer du type {@code Adapted}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BindingWriter adapterWriterFor(Class<?> raw) {
        if (globalAdapters.isEmpty() || raw == null || raw == Object.class) return null;
        // Match exact d'abord, puis assignable.
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

    /** Variante de {@link #writerFor(Type)} sans court-circuit adapter, pour éviter une récursion infinie. */
    private BindingWriter writerForRaw(Class<?> c) {
        if (c == null || c == Object.class) return dynamicWriter();
        if (c.isArray()) return arrayWriter(c.getComponentType());
        return cache.get(c);
    }

    /**
     * Cherche un {@code JsonbSerializer} global enregistré dont le type {@code T}
     * est assignable depuis {@code raw}. Renvoie un writer qui invoque
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

    /** Writer pour {@code @JsonbTypeSerializer} sur record component / Method / Field. */
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
            var ctor = serClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            ser = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbSerializer " + serClass, e);
        }
        return java.util.Optional.of((g, value) -> {
            if (value == null) { g.writeNull(); return; }
            ser.serialize(value, g, serContext);
        });
    }

    /** Writer qui résout au runtime par {@code value.getClass()} : nécessaire pour
     *  les collections/maps non typées (raw types) ou quand le type statique est {@code Object}. */
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
     * Résolution pour types paramétrés : Collection&lt;E&gt;, Map&lt;String,V&gt;, Optional&lt;E&gt;.
     * Le writer obtenu est <em>spécifique au type</em> et non caché par classe — la JVM réutilise
     * cependant la même instance pour des types identiques via memoisation interne (M5 : cache
     * dédié si besoin).
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
     * JSON-B 3.0 §3.3.1 — byte[] :
     * <ul>
     *   <li>BYTE (défaut) : array d'entiers signés [-128..127]</li>
     *   <li>BASE_64 : string base64 standard avec padding</li>
     *   <li>BASE_64_URL : string base64 URL-safe sans padding</li>
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
        // BYTE par défaut
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
        if (type == Number.class) return dynamicWriter();   // abstract → résoudre par valeur runtime
        if (Modifier.isAbstract(type.getModifiers()) && !type.isInterface()
                && !java.util.Map.class.isAssignableFrom(type)
                && !java.util.Collection.class.isAssignableFrom(type)) {
            return dynamicWriter();
        }
        // M4.5 : polymorphisme — si @JsonbTypeInfo (sur la classe ou un supertype),
        // émet un membre discriminant avant les membres du concrete type.
        var info = findTypeInfo(type);
        if (info != null) return polymorphicWriter(info);
        if (type.isRecord()) return resolveRecord(type);
        if (type.isPrimitive()) {
            // Boxé par l'appelant ; on ne devrait pas arriver ici hors cas tordu.
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
            try { accessor.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbName(c, c.getName());
            boolean nillable = isJsonbNillable(c) || writeNullValues;
            // M4.4f : @JsonbTypeAdapter → applique l'adapter avant écriture.
            // M4.4d : @JsonbDateFormat sur composant > M4.7 JSONB_DATE_FORMAT global > runtime ISO.
            BindingWriter w = customAdapterWriter(c)
                    .or(() -> customDateWriter(c))
                    .or(() -> globalDateWriter(c.getType()))
                    .orElseGet(() -> writerFor(c.getGenericType()));
            props.add(new Property(name, new MethodAccessor(accessor), w, nillable));
        }
        return (g, value) -> writeObject(g, value, props);
    }

    /**
     * Si le composant a {@code @JsonbTypeAdapter(class)}, retourne un writer qui :
     * 1. instancie l'adapter via son no-arg ctor,
     * 2. invoque {@code adaptToJson(value)} sur la valeur,
     * 3. délègue l'écriture du résultat au writer du type {@code Adapted} (déduit du
     *    super interface paramétré {@code JsonbAdapter<Original, Adapted>}).
     */
    private java.util.Optional<BindingWriter> customAdapterWriter(RecordComponent c) {
        var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        if (ann == null) {
            ann = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        }
        return adapterWriterFromAnnotation(ann);
    }

    /**
     * Cherche {@code @JsonbTypeAdapter} sur {@code member} (field ou method) ou,
     * en repli, sur le {@code underlying} field. Retourne le writer adapté ou empty.
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
            var ctor = adapterClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            adapter = ctor.newInstance();
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

    /** Variante de {@link #writerFor(Type)} sans court-circuit adapter. Préserve les ParameterizedType. */
    private BindingWriter writerForGeneric(java.lang.reflect.Type t) {
        if (t == null) return dynamicWriter();
        if (t instanceof java.lang.reflect.ParameterizedType p) return parameterizedWriter(p);
        if (t instanceof java.lang.reflect.GenericArrayType ga) return arrayWriter(rawOf(ga.getGenericComponentType()));
        Class<?> raw = rawOf(t);
        return writerForRaw(raw);
    }

    /** Examine les génériques de l'interface {@code JsonbAdapter<Original, Adapted>}. */
    @SuppressWarnings("rawtypes")
    static Class<?> findAdaptedType(Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass) {
        java.lang.reflect.Type t = findAdaptedGenericType(adapterClass);
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
        return Object.class;
    }

    /** Renvoie le {@link java.lang.reflect.Type} complet (avec génériques) du paramètre Adapted. */
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
     * Writer date pour un member donné (field ou method) en cherchant @JsonbDateFormat à
     * plusieurs niveaux : member > déclarant type > package > JsonbConfig.DATE_FORMAT.
     * Renvoie empty si {@code rawType} n'est pas un type date supporté ou si aucun pattern.
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
     * Writer numérique customisé via @JsonbNumberFormat (member > type > package > config).
     * Renvoie empty si pas applicable.
     */
    private java.util.Optional<BindingWriter> numberWriter(Class<?> rawType, java.lang.reflect.AnnotatedElement member, Class<?> declaringType) {
        if (!isNumericType(rawType)) return java.util.Optional.empty();
        var spec = findNumberFormatSpec(member, declaringType);
        if (spec == null) return java.util.Optional.empty();
        java.text.NumberFormat fmt;
        if ("##default".equals(spec.pattern()) || spec.pattern().isEmpty()) {
            fmt = java.text.NumberFormat.getInstance(spec.locale());
        } else {
            fmt = new java.text.DecimalFormat(spec.pattern(), new java.text.DecimalFormatSymbols(spec.locale()));
        }
        return java.util.Optional.of((g, value) -> {
            if (value == null) g.writeNull();
            else g.write(fmt.format(value));
        });
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
            // Walk superclass packages (anonymous inner classes héritent du parent)
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
        if (tag == null || tag.isEmpty() || "##default".equals(tag)) return java.util.Locale.getDefault();
        return java.util.Locale.forLanguageTag(tag);
    }

    /**
     * Détecte si un pattern utilise des caractères propres à {@link java.time.format.DateTimeFormatter}
     * non supportés par {@link java.text.SimpleDateFormat} (notamment {@code x}, {@code X}, {@code Z}
     * en certains nombres). On route alors la Date/Calendar via DateTimeFormatter après conversion
     * en {@code ZonedDateTime}.
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
                    // Convertir via ZonedDateTime pour aligner sur le format ZonedDateTime UTC
                    // (IJSON strict §3.5.1 demande que Date/Calendar produisent le même
                    // format qu'un ZonedDateTime).
                    var zdt = cal.toInstant().atZone(cal.getTimeZone().toZoneId());
                    var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
                    g.write(fmt.format(zdt));
                }
            };
        }
        // Duration / Period : format ISO 8601 fixe — les patterns DateTimeFormatter ne s'y appliquent pas.
        if (rawType == java.time.Duration.class || rawType == java.time.Period.class) {
            return (g, value) -> {
                if (value == null) { g.writeNull(); return; }
                g.write(value.toString());
            };
        }
        // java.time : convertir vers ZonedDateTime UTC quand nécessaire pour absorber les patterns
        // qui exigent des champs absolus (yyyy/MM/dd HH:mm:ss).
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
     * Si le composant a {@code @JsonbDateFormat}, retourne un writer qui formate
     * la valeur via {@link java.time.format.DateTimeFormatter#ofPattern}. Sinon empty.
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
            // Délègue à TemporalAccessor.format quand applicable.
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

        // 1) Découvrir tous les getters de la hiérarchie (incluant private/protected/package).
        // JSON-B 3.0 §3.7.1 : seuls les public getters sont considérés par défaut, mais
        // l'EXISTENCE d'un accesseur non-public masque la property (le field public ne suffit pas).
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
                    // Getter non-public → la property est masquée (même si un field public existe)
                    hiddenProps.add(propName);
                    continue;
                }
                // §3.7.1 : si le field underlying est static ou transient, skip.
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
        // Retire les props masquées au cas où on aurait à la fois public/non-public
        getterByProp.keySet().removeAll(hiddenProps);
        for (var e : getterByProp.entrySet()) {
            String propName = e.getKey();
            Method m = e.getValue();
            try { m.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbNameFromMethod(m, propName);
            Field underlying0 = findFieldByName(type, propName);
            boolean nillable = computeNillable(m, underlying0, type);
            // Date / Number format : @JsonbDateFormat / @JsonbNumberFormat sur method, field underlying, type, package, ou config global.
            Field underlying = findFieldByName(type, propName);
            java.lang.reflect.AnnotatedElement memberForDate = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class)
                    ? m : (underlying != null && underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class) ? underlying : null);
            java.lang.reflect.AnnotatedElement memberForNumber = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class)
                    ? m : (underlying != null && underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class) ? underlying : null);
            // §4.7 — @JsonbTypeAdapter / @JsonbTypeSerializer sur le getter ou son field underlying.
            final Field underlying2 = underlying;
            BindingWriter w = customAdapterWriter(m, underlying2)
                    .or(() -> customSerializerWriter(m, underlying2))
                    .or(() -> dateWriter(m.getReturnType(), memberForDate, type))
                    .or(() -> globalDateWriter(m.getReturnType()))
                    .or(() -> numberWriter(m.getReturnType(), memberForNumber, type))
                    .orElseGet(() -> writerFor(m.getGenericReturnType()));
            seen.add(propName);
            props.add(new Property(name, new MethodAccessor(m), w, nillable));
        }

        // 2) Champs publics non couverts par un getter et non masqués.
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

        // Application de PropertyVisibilityStrategy si configurée (config ou @JsonbVisibility).
        var visibility = effectiveVisibility(type);
        if (visibility != null) {
            var visibleProps = new ArrayList<Property>(props.size());
            for (Property pr : props) {
                if (isVisibleByStrategy(visibility, type, pr)) visibleProps.add(pr);
            }
            props.clear();
            props.addAll(visibleProps);
        }

        // Détection de doublons sur le nom JSON final.
        var names = new java.util.HashSet<String>();
        for (var pr : props) {
            if (!names.add(pr.name)) {
                throw new JsonbException("Duplicate JSON property name '" + pr.name + "' on " + type);
            }
        }

        // Tri selon @JsonbPropertyOrder + JsonbConfig.PROPERTY_ORDER_STRATEGY.
        applyPropertyOrder(type, props);

        if (props.isEmpty()) {
            // JSON-B 3.0 §3.7 : objet sans property visible → objet JSON vide {}.
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
        // Walk superclass chain for package visibility (anonymous inner classes héritent du parent)
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var fromPackage = packageVisibility(c);
            if (fromPackage != null) return fromPackage;
        }
        return propertyVisibilityStrategy;
    }

    /**
     * Lit la {@code @JsonbVisibility} déclarée sur le package-info de la classe.
     * Utilise plusieurs stratégies pour contourner les ClassLoader / module-path
     * où {@code Class.getPackage().getAnnotation()} peut retourner null si le
     * package-info n'a pas encore été chargé.
     */
    private jakarta.json.bind.config.PropertyVisibilityStrategy packageVisibility(Class<?> type) {
        var pkg = type.getPackage();
        if (pkg == null) return null;
        ClassLoader cl = type.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        // 1) Charger explicitement package-info et lire l'annotation directement.
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
        // 2) Package.getAnnotation (fonctionne uniquement après chargement)
        var pann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
        if (pann != null) {
            try { return pann.value().getDeclaredConstructor().newInstance(); }
            catch (Exception e) { throw new JsonbException("Cannot instantiate package @JsonbVisibility " + pann.value(), e); }
        }
        return null;
    }

    private static boolean isVisibleByStrategy(jakarta.json.bind.config.PropertyVisibilityStrategy s, Class<?> type, Property pr) {
        // Spec §4.5 : on consulte BOTH le field underlying (si présent) ET la méthode.
        // Une property est visible si l'une ou l'autre return true (logique OR).
        if (pr.accessor instanceof FieldAccessor fa) {
            return s.isVisible(fa.f);
        }
        if (pr.accessor instanceof MethodAccessor ma) {
            boolean methodVisible = s.isVisible(ma.m);
            // Cherche le field underlying par nom de bean property
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
     * Applique l'ordre :
     * <ol>
     *   <li>Property dans @JsonbPropertyOrder dans l'ordre déclaré ;</li>
     *   <li>autres properties triées via {@link jakarta.json.bind.config.PropertyOrderStrategy}
     *       (LEXICOGRAPHICAL = défaut, REVERSE, ANY = ordre d'insertion).</li>
     * </ol>
     */
    private void applyPropertyOrder(Class<?> type, List<Property> props) {
        jakarta.json.bind.annotation.JsonbPropertyOrder order = null;
        for (Class<?> c = type; c != null && c != Object.class && order == null; c = c.getSuperclass()) {
            order = c.getAnnotation(jakarta.json.bind.annotation.JsonbPropertyOrder.class);
        }
        String[] explicit = order == null ? new String[0] : order.value();
        // Resolve explicit names through naming strategy: les noms du @JsonbPropertyOrder se réfèrent
        // soit au nom de property (camelCase) soit au nom JSON. On accepte les deux.
        // On compare le nom JSON final ; si l'utilisateur a annoté avec le nom JSON, OK ; sinon on tente
        // le nom de property non transformé.
        var byName = new java.util.LinkedHashMap<String, Property>();
        for (var p : props) byName.put(p.name, p);
        var ordered = new ArrayList<Property>(props.size());
        for (String n : explicit) {
            Property p = byName.remove(n);
            if (p == null) {
                // Try with naming strategy applied
                String transformed = applyNamingStrategy(n, false);
                p = byName.remove(transformed);
            }
            if (p != null) ordered.add(p);
        }
        var rest = new ArrayList<>(byName.values());
        String strat = propertyOrderStrategy;
        // Default JSON-B 3.0 §4.4 : LEXICOGRAPHICAL.
        if (strat == null) strat = "LEXICOGRAPHICAL";
        // Group by declaring class (parent → child) for class hierarchy ordering
        var byClass = new java.util.LinkedHashMap<Class<?>, java.util.List<Property>>();
        // Build chain super→sub
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
     * Applique la stratégie de naming JSON-B 3.0 §4.1.1 sur un nom déjà déterminé via
     * convention bean (ou via @JsonbProperty/JsonbPropertyOrder qui ont la priorité).
     *
     * <p>Si {@code annotated} est vrai (le nom vient d'une annotation explicite), on ne
     * transforme pas — l'annotation a la priorité absolue.</p>
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
     * JSON-B 3.0 §4.7 : si {@code @JsonbTransient} apparaît sur un membre (field/getter/setter)
     * et qu'une AUTRE annotation Jsonb apparaît sur le même membre OU sur le pair (field/getter/setter)
     * de la même property, c'est une combinaison invalide → JsonbException.
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
     * Calcule le statut nillable d'une property :
     * <ol>
     *   <li>@JsonbProperty(nillable=true) ou @JsonbNillable explicite → true (annotation gagne)</li>
     *   <li>@JsonbNillable(false) explicite → false (annotation gagne)</li>
     *   <li>type level @JsonbNillable → true</li>
     *   <li>package level @JsonbNillable → true</li>
     *   <li>JsonbConfig.NULL_VALUES → true</li>
     *   <li>Sinon → false (default : omit)</li>
     * </ol>
     */
    private boolean computeNillable(Method getter, Field underlying, Class<?> type) {
        // Annotations directes sur member
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

    /** Cherche un field (public, protected, package, private) sur la classe ou ses parents. */
    private static Field findFieldByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Cherche {@code @JsonbTypeInfo} sur {@code type} ou ses supertypes (interfaces et
     * superclasses). Spec §4.8 : peut être déclarée sur l'interface sealed parente.
     *
     * <p>Pour une chaîne linéaire d'héritage (A → B → C où chaque interface porte
     * @JsonbTypeInfo), retourne l'annotation la PLUS PROCHE (la plus spécifique).
     * Lève {@link JsonbException} uniquement quand plusieurs interfaces SŒURS portent
     * indépendamment l'annotation (vraie multi-inheritance — TCK
     * {@code TypeInfoExceptionsTest.testSerializeTypeInfoMultiInheritance}).</p>
     */
    static jakarta.json.bind.annotation.JsonbTypeInfo findTypeInfo(Class<?> type) {
        if (type == null || type == Object.class) return null;
        var direct = type.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
        if (direct != null) return direct;
        // Collecter sur les interfaces directes ET la superclass.
        java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> direct1 = new java.util.ArrayList<>();
        for (Class<?> i : type.getInterfaces()) {
            var d = i.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (d != null) direct1.add(d);
        }
        if (type.getSuperclass() != null && type.getSuperclass() != Object.class) {
            var d = type.getSuperclass().getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (d != null) direct1.add(d);
        }
        // Si plusieurs ancêtres directs portent @JsonbTypeInfo, multi-inheritance non supportée.
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
        // Aucune annotation directe : chercher récursivement sur les ancêtres.
        for (Class<?> i : type.getInterfaces()) {
            var f = findTypeInfo(i);
            if (f != null) return f;
        }
        return findTypeInfo(type.getSuperclass());
    }

    /**
     * Collecte la chaîne complète de {@code @JsonbTypeInfo} dans l'ordre
     * <strong>parent → enfant</strong> (le plus général en premier). Utilisé pour
     * écrire des discriminators en cascade (MultipleTypeInfoTest).
     */
    static java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> typeInfoChain(Class<?> type) {
        java.util.List<jakarta.json.bind.annotation.JsonbTypeInfo> chain = new java.util.ArrayList<>();
        // Walk linéaire : à chaque niveau, prendre l'annotation directe la plus PROCHE
        // (depuis type vers les ancêtres) et l'ajouter au CHEMIN.
        Class<?> current = type;
        while (current != null && current != Object.class) {
            var direct = current.getAnnotation(jakarta.json.bind.annotation.JsonbTypeInfo.class);
            if (direct != null) chain.add(0, direct); // parent en premier
            // Cherche dans les interfaces directes
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
     * Validations §4.8 sur {@code @JsonbTypeInfo} avant écriture/lecture :
     * <ul>
     *   <li>chaque alias doit pointer vers un sous-type assignable</li>
     *   <li>le {@code key} ne doit pas collider avec le nom d'une propriété de la classe</li>
     * </ul>
     */
    static void validateTypeInfo(Class<?> type, jakarta.json.bind.annotation.JsonbTypeInfo info) {
        if (info == null) return;
        // Trouver la classe qui PORTE directement l'annotation.
        Class<?> bearer = bearerOfTypeInfo(type, info);
        if (bearer != null) {
            for (var sub : info.value()) {
                if (!bearer.isAssignableFrom(sub.type())) {
                    throw new JsonbException("@JsonbSubtype alias '" + sub.alias() + "' references "
                            + sub.type().getName() + " which is not a subtype of " + bearer.getName());
                }
            }
        }
        // Vérifier collision du discriminator key avec une propriété de la classe.
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
     * Cherche dans {@code info.value()} l'alias correspondant à un subtype assignable
     * depuis {@code concrete}. Retourne null si aucun match (peut arriver pour des
     * niveaux intermédiaires d'une cascade, ex. Animal/Dog interfaces sans subtype direct).
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
     * Writer polymorphe : pour chaque {@code value}, identifie l'alias matching dans
     * {@code typeInfo.value()}, écrit {@code @key:alias}, puis sérialise les membres
     * de la classe concrète.
     */
    private BindingWriter polymorphicWriter(jakarta.json.bind.annotation.JsonbTypeInfo info) {
        return (g, value) -> {
            if (value == null) { g.writeNull(); return; }
            Class<?> concrete = value.getClass();
            // Validations §4.8 sur la première annotation rencontrée.
            validateTypeInfo(concrete, info);
            // Cascade : écrire chaque (key, alias) de la chaîne d'@JsonbTypeInfo
            // depuis l'ancêtre le plus général jusqu'à l'enfant le plus spécifique
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
            try { accessor.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbName(c, c.getName());
            boolean nillable = isJsonbNillable(c) || writeNullValues;
            BindingWriter w = customAdapterWriter(c)
                    .or(() -> customDateWriter(c))
                    .or(() -> globalDateWriter(c.getType()))
                    .orElseGet(() -> writerFor(c.getGenericType()));
            props.add(new Property(name, new MethodAccessor(accessor), w, nillable));
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
            props.add(new Property(name, new MethodAccessor(m), w, nillable));
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
        // Appliquer le tri parent → enfant + lex (cas hiérarchique avec @JsonbTypeInfo,
        // MultipleTypeInfoTest.testSerializeMultipleTypeInfoInSingleChain).
        applyPropertyOrder(type, props);
        return props;
    }

    /**
     * Vrai si le {@link RecordComponent} ou son accesseur portent {@code @JsonbTransient}.
     * Spec §4.7 : l'annotation peut être présente sur le composant lui-même ou sur la
     * méthode d'accès (synthétique pour les records).
     */
    private static boolean isJsonbTransient(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) return true;
        Method accessor = c.getAccessor();
        return accessor.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    /**
     * Vrai si le composant ou son accesseur portent {@code @JsonbNillable}.
     * Spec §4.3.3 : force l'écriture du membre même quand sa valeur est null.
     */
    private static boolean isJsonbNillable(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class)) return true;
        Method accessor = c.getAccessor();
        return accessor.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class);
    }

    /** Renommage via {@code @JsonbProperty(name)} sur un component ; fallback {@code defaultName}. */
    private String jsonbName(RecordComponent c, String defaultName) {
        var prop = c.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        var accessorProp = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (accessorProp != null && !accessorProp.value().isEmpty()) return applyNamingStrategy(accessorProp.value(), true);
        return applyNamingStrategy(defaultName, false);
    }

    /** Renommage via {@code @JsonbProperty(name)} sur un field ; fallback {@code defaultName}. */
    private String jsonbName(Field f, String defaultName) {
        var prop = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        return applyNamingStrategy(defaultName, false);
    }

    /** Renommage via {@code @JsonbProperty(name)} sur un method, son setter pair ou le field underlying. */
    String jsonbNameFromMethod(Method m, String defaultName) {
        var prop = m.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNamingStrategy(prop.value(), true);
        // Recherche aussi sur le setter associé et le field underlying (spec §4.1.2).
        Class<?> declaring = m.getDeclaringClass();
        Field underlying = findFieldByName(declaring, defaultName);
        if (underlying != null) {
            var fp = underlying.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (fp != null && !fp.value().isEmpty()) return applyNamingStrategy(fp.value(), true);
        }
        // Setter associé
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
     * Convention JavaBean §3.7 : {@code getXxx} → {@code xxx} ; {@code isXxx} (boolean
     * uniquement) → {@code xxx}. Retourne {@code null} si la méthode n'est pas un accesseur.
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

    /** Convention JavaBean : {@code setXxx} → {@code xxx}. */
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
            Object v;
            try {
                v = p.accessor.read(value);
            } catch (Throwable t) {
                throw new JsonbException("Failed to read property " + p.name, t);
            }
            if (v == null) {
                // Spec §3.14.2 : null members omis par défaut.
                // §4.3.3 @JsonbNillable : force-include.
                if (p.nillable) {
                    g.writeKey(p.name);
                    g.writeNull();
                }
                continue;
            }
            if (v instanceof java.util.Optional<?> opt && opt.isEmpty()) {
                // Optional.empty() = absence sémantique → membre omis (sauf @JsonbNillable).
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
        // JSON-B 3.0 §3.5.1 : java.util.Date → ZonedDateTime UTC ISO format
        static final BindingWriter UTIL_DATE = (g, v) -> {
            var d = (java.util.Date) v;
            g.write(d.toInstant().atZone(java.time.ZoneId.of("UTC")).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
        };
        static final BindingWriter CALENDAR = (g, v) -> {
            var c = (java.util.Calendar) v;
            var zdt = c.toInstant().atZone(c.getTimeZone().toZoneId());
            // JSON-B 3.0 §3.5.1 : si time = 00:00:00.000, format ISO_OFFSET_DATE.
            if (zdt.getHour() == 0 && zdt.getMinute() == 0 && zdt.getSecond() == 0 && zdt.getNano() == 0) {
                g.write(zdt.toLocalDate().atStartOfDay(zdt.getZone()).toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE));
            } else {
                g.write(zdt.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            }
        };
        static final BindingWriter TIMEZONE = (g, v) -> g.write(((java.util.TimeZone) v).getID());

        // JSON-B §3.6 : JSON-P types passent par g.write(JsonValue)
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
