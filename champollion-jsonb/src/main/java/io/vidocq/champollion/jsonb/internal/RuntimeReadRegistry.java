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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registre de {@link BindingReader} symétrique à {@link RuntimeBindingRegistry}.
 *
 * <p>Cache {@link ClassValue} pour les classes simples + map concurrente pour les types
 * paramétrés (clé = {@link Type#getTypeName()}).</p>
 */
final class RuntimeReadRegistry {

    private final ClassValue<BindingReader> classCache = new ClassValue<>() {
        @Override protected BindingReader computeValue(Class<?> type) { return resolveClass(type); }
    };

    private final java.util.concurrent.ConcurrentHashMap<String, BindingReader> typeCache = new java.util.concurrent.ConcurrentHashMap<>();

    BindingReader readerFor(Type t) {
        if (t instanceof Class<?> c) {
            if (c.isArray()) return arrayReader(c.getComponentType());
            return classCache.get(c);
        }
        if (t instanceof java.lang.reflect.ParameterizedType p) {
            return typeCache.computeIfAbsent(p.getTypeName(), k -> parameterizedReader(p));
        }
        return classCache.get((Class<?>) t);
    }

    // ===== Resolution by raw class =====

    private BindingReader resolveClass(Class<?> type) {
        BindingReader b = Builtins.lookup(type);
        if (b != null) return b;
        if (type.isEnum()) return enumReader(type);
        if (type.isRecord()) return resolveRecord(type);
        if (type.isPrimitive()) return classCache.get(box(type));
        if (java.util.Map.class.isAssignableFrom(type)) return mapReader(this::dynamicValue);
        if (java.util.Collection.class.isAssignableFrom(type)) return collectionReader(this::dynamicValue, type);
        if (type == Optional.class) return optionalReader(this::dynamicValue);
        if (type == Object.class) return this::dynamicValue;
        return resolvePojo(type);
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
        Map<String, Integer> indexByName = new HashMap<>(comps.length * 2);
        for (int i = 0; i < comps.length; i++) {
            paramTypes[i] = comps[i].getType();
            readers[i] = readerFor(comps[i].getGenericType());
            indexByName.put(comps[i].getName(), i);
        }
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new JsonbException("Canonical record constructor not found for " + type, e);
        }
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        final Constructor<?> finalCtor = ctor;

        return parser -> readObjectAndConstruct(parser, finalCtor, paramTypes, readers, indexByName);
    }

    private BindingReader resolvePojo(Class<?> type) {
        // POJO : ctor sans arg + champs publics. Pas de getter discovery ici (M4.2 minimal).
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new JsonbException("No no-arg constructor for " + type, e);
        }
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        Map<String, Field> fieldsByName = new HashMap<>();
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            try { f.setAccessible(true); } catch (Exception ignore) {}
            fieldsByName.put(f.getName(), f);
        }
        return parser -> readObjectAndAssign(parser, ctor, fieldsByName);
    }

    private Object readObjectAndConstruct(JsonParser p, Constructor<?> ctor,
                                          Class<?>[] paramTypes, BindingReader[] readers,
                                          Map<String, Integer> indexByName) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) {
            throw new JsonbException("Expected object, got " + e);
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultFor(paramTypes[i]);
        }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            Integer idx = indexByName.get(key);
            if (idx == null) {
                skipValue(p);
            } else {
                args[idx] = readers[idx].read(p);
            }
        }
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to instantiate record: " + ex.getMessage(), ex);
        }
    }

    private Object readObjectAndAssign(JsonParser p, Constructor<?> ctor, Map<String, Field> fields) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object, got " + e);
        Object inst;
        try {
            inst = ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to instantiate POJO: " + ex.getMessage(), ex);
        }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            Field f = fields.get(key);
            if (f == null) { skipValue(p); continue; }
            BindingReader r = readerFor(f.getGenericType());
            Object value = r.read(p);
            try { f.set(inst, value); }
            catch (IllegalAccessException ex) { throw new JsonbException("Cannot set field " + f, ex); }
        }
        return inst;
    }

    private static Object defaultFor(Class<?> type) {
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

    /** Saute la valeur courante (peut être un objet ou array imbriqué). */
    private static void skipValue(JsonParser p) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.START_OBJECT) p.skipObject();
        else if (e == JsonParser.Event.START_ARRAY) p.skipArray();
        // sinon scalaire → consommé par next()
    }

    // ===== Parameterized =====

    private BindingReader parameterizedReader(java.lang.reflect.ParameterizedType p) {
        Class<?> raw = (Class<?>) p.getRawType();
        Type[] args = p.getActualTypeArguments();
        if (java.util.Map.class.isAssignableFrom(raw)) {
            BindingReader vr = args.length >= 2 ? readerFor(args[1]) : (BindingReader) this::dynamicValue;
            return mapReader(vr);
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

    private BindingReader mapReader(BindingReader valueReader) {
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object for Map, got " + e);
            var out = new LinkedHashMap<String, Object>();
            while ((e = parser.next()) != JsonParser.Event.END_OBJECT) {
                if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
                String key = parser.getString();
                out.put(key, valueReader.read(parser));
            }
            return out;
        };
    }

    private BindingReader collectionReader(BindingReader elemReader, Class<?> raw) {
        boolean asSet = java.util.Set.class.isAssignableFrom(raw);
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
            var out = asSet ? new LinkedHashSet<Object>() : new ArrayList<Object>();
            while (true) {
                JsonParser.Event tok = parser.next();
                if (tok == JsonParser.Event.END_ARRAY) return out;
                JsonParser primed = new PrimedParser(tok, parser);
                out.add(elemReader.read(primed));
            }
        };
    }

    // ===== Optional / Builtins / arrays =====

    private BindingReader optionalReader(BindingReader inner) {
        return parser -> {
            // Lit la valeur ; null → Optional.empty
            Object v = inner.read(parser);
            return v == null ? Optional.empty() : Optional.of(v);
        };
    }

    private BindingReader arrayReader(Class<?> componentType) {
        if (componentType == int.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, ((BindingReader) (q -> { q.next(); return q.getInt(); })));
                if (tmp == null) return null;
                int[] out = new int[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Integer) tmp.get(i);
                return out;
            };
        }
        if (componentType == long.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return q.getLong(); });
                if (tmp == null) return null;
                long[] out = new long[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Long) tmp.get(i);
                return out;
            };
        }
        if (componentType == double.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return q.getBigDecimal().doubleValue(); });
                if (tmp == null) return null;
                double[] out = new double[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Double) tmp.get(i);
                return out;
            };
        }
        if (componentType == boolean.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> {
                    JsonParser.Event ev = q.next();
                    return ev == JsonParser.Event.VALUE_TRUE;
                });
                if (tmp == null) return null;
                boolean[] out = new boolean[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Boolean) tmp.get(i);
                return out;
            };
        }
        BindingReader elem = readerFor(componentType);
        return parser -> {
            List<Object> tmp = (List<Object>) collectArray(parser, elem);
            if (tmp == null) return null;
            Object out = java.lang.reflect.Array.newInstance(componentType, tmp.size());
            for (int i = 0; i < tmp.size(); i++) java.lang.reflect.Array.set(out, i, tmp.get(i));
            return out;
        };
    }

    /**
     * Lit un array et applique {@code elem.read} pour chaque élément. Gère le cas
     * où l'event de tête est consommé en regardant {@code hasNext} avant de relancer.
     */
    private static Object collectArray(JsonParser p, BindingReader elem) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
        var out = new ArrayList<Object>();
        // Wrapper "lookahead" : on lit next, si END_ARRAY on sort, sinon on rebranche un
        // mini-parser qui restitue cet event au lecteur élément.
        while (true) {
            JsonParser.Event tok = p.next();
            if (tok == JsonParser.Event.END_ARRAY) return out;
            // Replay : crée un parser "amorcé" qui retourne tok puis délègue.
            JsonParser primed = new PrimedParser(tok, p);
            out.add(elem.read(primed));
        }
    }

    /**
     * Parser qui ré-émet un event consommé en amont, puis délègue le reste au parser sous-jacent.
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

    /** Lit la valeur courante en tant qu'Object / déterminée dynamiquement. */
    private Object dynamicValue(JsonParser p) {
        JsonParser.Event e = p.next();
        return switch (e) {
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER -> {
                BigDecimal bd = p.getBigDecimal();
                if (p.isIntegralNumber()) yield bd.toBigIntegerExact().longValueExact() <= Integer.MAX_VALUE && bd.toBigIntegerExact().longValueExact() >= Integer.MIN_VALUE
                        ? bd.intValueExact() : bd.toBigIntegerExact();
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
            // recursion on dynamic
            JsonParser primed;
            // We need to read a value, but dynamicValue advances next() itself : no extra primed needed
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

    // ===== Builtins =====

    private static final class Builtins {

        static BindingReader lookup(Class<?> type) {
            if (type == String.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getString(); };
            if (type == Integer.class || type == int.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getInt(); };
            if (type == Long.class || type == long.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getLong(); };
            if (type == Double.class || type == double.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().doubleValue(); };
            if (type == Float.class || type == float.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().floatValue(); };
            if (type == Short.class || type == short.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : (short) p.getInt(); };
            if (type == Byte.class || type == byte.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : (byte) p.getInt(); };
            if (type == Boolean.class || type == boolean.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                if (e == JsonParser.Event.VALUE_TRUE) return Boolean.TRUE;
                if (e == JsonParser.Event.VALUE_FALSE) return Boolean.FALSE;
                throw new JsonbException("Expected boolean, got " + e);
            };
            if (type == Character.class || type == char.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                return s.isEmpty() ? '\0' : s.charAt(0);
            };
            if (type == BigDecimal.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal(); };
            if (type == BigInteger.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().toBigIntegerExact(); };
            if (type == UUID.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : UUID.fromString(p.getString()); };
            if (type == Instant.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : Instant.parse(p.getString()); };
            if (type == LocalDate.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : LocalDate.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE); };
            if (type == LocalDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : LocalDateTime.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME); };
            if (type == OffsetDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : OffsetDateTime.parse(p.getString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME); };
            if (type == ZonedDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : ZonedDateTime.parse(p.getString(), DateTimeFormatter.ISO_ZONED_DATE_TIME); };
            return null;
        }
    }
}
