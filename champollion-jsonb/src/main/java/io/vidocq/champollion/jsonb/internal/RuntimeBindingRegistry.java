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

    private final ClassValue<BindingWriter> cache = new ClassValue<>() {
        @Override protected BindingWriter computeValue(Class<?> type) { return resolveClass(type); }
    };

    BindingWriter writerFor(Type t) {
        if (t instanceof Class<?> c) return cache.get(c);
        // Generic types : M4.3 — pour M4.1 on dégrade en raw type.
        if (t == null) return Builtins.OBJECT;
        return cache.get(rawOf(t));
    }

    private static Class<?> rawOf(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
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
            Method accessor = c.getAccessor();
            try { accessor.setAccessible(true); } catch (Exception ignore) {}
            props.add(new Property(c.getName(), new MethodAccessor(accessor), writerFor(c.getGenericType())));
        }
        return (g, value) -> writeObject(g, value, props);
    }

    private BindingWriter resolvePojo(Class<?> type) {
        // M4.1 : champs publics + getters conventionnels.
        var props = new ArrayList<Property>();
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            try { f.setAccessible(true); } catch (Exception ignore) {}
            props.add(new Property(f.getName(), new FieldAccessor(f), writerFor(f.getGenericType())));
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

    private record Property(String name, Accessor accessor, BindingWriter writer) {}

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
