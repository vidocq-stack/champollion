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

    /** Constructeur par défaut : pas de date format global, null members omis. */
    RuntimeBindingRegistry() {
        this(null, false);
    }

    /**
     * @param defaultDateFormat pattern global pour les types java.time (JSONB_DATE_FORMAT)
     * @param writeNullValues   si true, écrit les null au lieu de les omettre (JSONB_NULL_VALUES)
     */
    RuntimeBindingRegistry(String defaultDateFormat, boolean writeNullValues) {
        this.defaultDateFormat = defaultDateFormat;
        this.writeNullValues = writeNullValues;
    }

    private final ClassValue<BindingWriter> cache = new ClassValue<>() {
        @Override protected BindingWriter computeValue(Class<?> type) { return resolveClass(type); }
    };

    BindingWriter writerFor(Type t) {
        if (t == null) return dynamicWriter();
        if (t instanceof Class<?> c) {
            if (c.isArray()) return arrayWriter(c.getComponentType());
            if (c == Object.class) return dynamicWriter();
            return cache.get(c);
        }
        if (t instanceof java.lang.reflect.ParameterizedType p) return parameterizedWriter(p);
        return cache.get(rawOf(t));
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    private java.util.Optional<BindingWriter> customAdapterWriter(RecordComponent c) {
        var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        if (ann == null) {
            ann = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        }
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass = ann.value();
        jakarta.json.bind.adapter.JsonbAdapter adapter;
        try {
            adapter = adapterClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbAdapter " + adapterClass, e);
        }
        Class<?> adaptedType = findAdaptedType(adapterClass);
        BindingWriter inner = writerFor(adaptedType);
        return java.util.Optional.of((g, value) -> {
            Object adapted;
            try { adapted = adapter.adaptToJson(value); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on toJson: " + ex.getMessage(), ex); }
            if (adapted == null) g.writeNull();
            else inner.write(g, adapted);
        });
    }

    /** Examine les génériques de l'interface {@code JsonbAdapter<Original, Adapted>}. */
    @SuppressWarnings("rawtypes")
    static Class<?> findAdaptedType(Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass) {
        for (java.lang.reflect.Type t : adapterClass.getGenericInterfaces()) {
            if (t instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() == jakarta.json.bind.adapter.JsonbAdapter.class) {
                java.lang.reflect.Type adapted = pt.getActualTypeArguments()[1];
                if (adapted instanceof Class<?> c) return c;
                if (adapted instanceof java.lang.reflect.ParameterizedType ap) return (Class<?>) ap.getRawType();
            }
        }
        return Object.class;
    }

    /**
     * Writer global pour les types {@code java.time.*} basé sur {@code JSONB_DATE_FORMAT}
     * (config). Renvoie empty si pas de pattern global ou si {@code rawType} n'est pas
     * un type java.time supporté.
     */
    private java.util.Optional<BindingWriter> globalDateWriter(Class<?> rawType) {
        if (defaultDateFormat == null) return java.util.Optional.empty();
        if (rawType != java.time.LocalDate.class
                && rawType != java.time.LocalDateTime.class
                && rawType != java.time.OffsetDateTime.class
                && rawType != java.time.ZonedDateTime.class
                && rawType != java.time.Instant.class) {
            return java.util.Optional.empty();
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(defaultDateFormat);
        return java.util.Optional.of((g, value) -> {
            if (value == null) { g.writeNull(); return; }
            g.write(fmt.format((java.time.temporal.TemporalAccessor) value));
        });
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
        var props = new ArrayList<Property>();
        var seen = new java.util.HashSet<String>();

        // 1) JavaBean getters publics (priorité §3.7) : getXxx() / isXxx() boolean.
        for (Method m : type.getMethods()) {
            int mods = m.getModifiers();
            if (Modifier.isStatic(mods)) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() == void.class) continue;
            String propName = beanPropertyOf(m);
            if (propName == null) continue;
            if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            try { m.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbNameFromMethod(m, propName);
            boolean nillable = m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class)
                    || writeNullValues;
            BindingWriter w = globalDateWriter(m.getReturnType()).orElseGet(() -> writerFor(m.getGenericReturnType()));
            seen.add(propName);
            props.add(new Property(name, new MethodAccessor(m), w, nillable));
        }

        // 2) Champs publics non couverts par un getter.
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            if (f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            if (seen.contains(f.getName())) continue;   // déjà couvert par un getter
            try { f.setAccessible(true); } catch (Exception ignore) {}
            String name = jsonbName(f, f.getName());
            boolean nillable = f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNillable.class)
                    || writeNullValues;
            BindingWriter w = globalDateWriter(f.getType()).orElseGet(() -> writerFor(f.getGenericType()));
            props.add(new Property(name, new FieldAccessor(f), w, nillable));
        }

        if (props.isEmpty()) {
            // Fallback : toString() pour les types opaques sans builtin et sans champs.
            return (g, value) -> {
                if (value == null) g.writeNull();
                else g.write(value.toString());
            };
        }
        return (g, value) -> writeObject(g, value, props);
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
    private static String jsonbName(RecordComponent c, String defaultName) {
        var prop = c.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        var accessorProp = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (accessorProp != null && !accessorProp.value().isEmpty()) return accessorProp.value();
        return defaultName;
    }

    /** Renommage via {@code @JsonbProperty(name)} sur un field ; fallback {@code defaultName}. */
    private static String jsonbName(Field f, String defaultName) {
        var prop = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        return defaultName;
    }

    /** Renommage via {@code @JsonbProperty(name)} sur un method ; fallback {@code defaultName}. */
    static String jsonbNameFromMethod(Method m, String defaultName) {
        var prop = m.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        return defaultName;
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
        static final BindingWriter FLOAT = (g, v) -> g.write(((Number) v).doubleValue());
        static final BindingWriter SHORT = (g, v) -> g.write(((Number) v).intValue());
        static final BindingWriter BYTE = (g, v) -> g.write(((Number) v).intValue());
        static final BindingWriter BIG_DECIMAL = (g, v) -> g.write((BigDecimal) v);
        static final BindingWriter BIG_INTEGER = (g, v) -> g.write((BigInteger) v);

        static final BindingWriter BOOLEAN = (g, v) -> g.write((Boolean) v);
        static final BindingWriter CHAR = (g, v) -> g.write(String.valueOf((Character) v));

        static final BindingWriter UUID_W = (g, v) -> g.write(((UUID) v).toString());
        static final BindingWriter ENUM = (g, v) -> g.write(((Enum<?>) v).name());

        static final BindingWriter INSTANT = (g, v) -> g.write(((Instant) v).toString());
        static final BindingWriter LOCAL_DATE = (g, v) -> g.write(((LocalDate) v).format(DateTimeFormatter.ISO_LOCAL_DATE));
        static final BindingWriter LOCAL_DATETIME = (g, v) -> g.write(((LocalDateTime) v).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        static final BindingWriter OFFSET_DATETIME = (g, v) -> g.write(((OffsetDateTime) v).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        static final BindingWriter ZONED_DATETIME = (g, v) -> g.write(((ZonedDateTime) v).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));

        static BindingWriter lookup(Class<?> type) {
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
            if (type == Instant.class) return INSTANT;
            if (type == LocalDate.class) return LOCAL_DATE;
            if (type == LocalDateTime.class) return LOCAL_DATETIME;
            if (type == OffsetDateTime.class) return OFFSET_DATETIME;
            if (type == ZonedDateTime.class) return ZONED_DATETIME;
            return null;
        }
    }
}
