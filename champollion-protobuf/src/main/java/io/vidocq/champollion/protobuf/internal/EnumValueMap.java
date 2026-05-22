package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache de mappings {@code Java enum constant ↔ proto int value}.
 *
 * <p>Construit une fois par {@code Class<? extends Enum<?>>}, partagé entre
 * tous les call-sites (read/write/scalarSize/JSON). Garde deux maps :</p>
 *
 * <ul>
 *   <li>{@code int → Enum<?>} : utilisé en lecture (wire ou JSON) pour
 *       résoudre une valeur entière reçue vers la constante Java. En cas
 *       d'aliasing, la première constante déclarée gagne.</li>
 *   <li>{@code Enum<?> → int} : utilisé en écriture pour récupérer la valeur
 *       proto à émettre depuis une instance d'enum.</li>
 * </ul>
 *
 * <p>Source du mapping :</p>
 * <ol>
 *   <li>Si la constante porte {@link ProtoEnumValue}, sa {@code value()} est
 *       utilisée (peut être négative, et peut être identique à une autre
 *       pour de l'aliasing).</li>
 *   <li>Sinon, fallback sur {@link Enum#ordinal()} (rétro-compat).</li>
 * </ol>
 */
public final class EnumValueMap {

    private static final ConcurrentHashMap<Class<?>, EnumValueMap> CACHE = new ConcurrentHashMap<>();

    private final Map<Integer, Enum<?>> intToEnum;
    private final Map<Enum<?>, Integer> enumToInt;

    private EnumValueMap(Map<Integer, Enum<?>> intToEnum, Map<Enum<?>, Integer> enumToInt) {
        this.intToEnum = intToEnum;
        this.enumToInt = enumToInt;
    }

    public static EnumValueMap forClass(Class<?> enumClass) {
        EnumValueMap cached = CACHE.get(enumClass);
        if (cached != null) return cached;
        EnumValueMap built = build(enumClass);
        EnumValueMap prev = CACHE.putIfAbsent(enumClass, built);
        return prev != null ? prev : built;
    }

    private static EnumValueMap build(Class<?> enumClass) {
        if (!enumClass.isEnum()) {
            throw new IllegalArgumentException(enumClass + " is not an enum");
        }
        Map<Integer, Enum<?>> intToEnum = new HashMap<>();
        Map<Enum<?>, Integer> enumToInt = new HashMap<>();
        Object[] constants = enumClass.getEnumConstants();
        for (Object c : constants) {
            Enum<?> e = (Enum<?>) c;
            int value;
            try {
                ProtoEnumValue pev = enumClass.getField(e.name()).getAnnotation(ProtoEnumValue.class);
                value = pev != null ? pev.value() : e.ordinal();
            } catch (NoSuchFieldException nsfe) {
                value = e.ordinal();
            }
            enumToInt.put(e, value);
            // En cas d'aliasing, la première constante déclarée gagne (putIfAbsent).
            intToEnum.putIfAbsent(value, e);
        }
        return new EnumValueMap(intToEnum, enumToInt);
    }

    /** Retourne la constante d'enum correspondant à {@code value}, ou {@code null}
     * si non mappée (unknown value — proto3 forward-compat). */
    public Enum<?> byValue(int value) {
        return intToEnum.get(value);
    }

    /** Retourne la valeur proto correspondant à {@code constant}. */
    public int valueOf(Enum<?> constant) {
        Integer v = enumToInt.get(constant);
        return v != null ? v : constant.ordinal();
    }
}
