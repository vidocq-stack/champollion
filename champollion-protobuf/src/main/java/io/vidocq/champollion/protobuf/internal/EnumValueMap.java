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
package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of mappings {@code Java enum constant ↔ proto int value}.
 *
 * <p>Built once per {@code Class<? extends Enum<?>>}, shared between
 * all call sites (read/write/scalarSize/JSON). Keeps two maps:</p>
 *
 * <ul>
 *   <li>{@code int → Enum<?>} : used in reads (wire or JSON) to
 *       resolve an incoming integer value to the Java constant. In case of
 *       aliasing, the first declared constant wins.</li>
 *   <li>{@code Enum<?> → int} : used in writes to recover the proto value
 *       to emit from an enum instance.</li>
 * </ul>
 *
 * <p>Mapping source:</p>
 * <ol>
 *   <li>If the constant carries {@link ProtoEnumValue}, its {@code value()} is
 *       used (may be negative, and may be identical to another one
 *       for aliasing).</li>
 *   <li>Otherwise, fallback to {@link Enum#ordinal()} (backward compat).</li>
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
            // In case of aliasing, the first declared constant wins (putIfAbsent).
            intToEnum.putIfAbsent(value, e);
        }
        return new EnumValueMap(intToEnum, enumToInt);
    }

    /** Returns the enum constant corresponding to {@code value}, or {@code null}
     * if unmapped (unknown value — proto3 forward compat). */
    public Enum<?> byValue(int value) {
        return intToEnum.get(value);
    }

    /** Returns the proto value corresponding to {@code constant}. */
    public int valueOf(Enum<?> constant) {
        Integer v = enumToInt.get(constant);
        return v != null ? v : constant.ordinal();
    }
}
