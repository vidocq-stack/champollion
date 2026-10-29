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

import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** How {@link GenericTypes} replaces the variables a class's hierarchy binds. */
class GenericTypesTest {

    interface Source<S> {
    }

    static class Base<T, K> {
        T one;
        T[] many;
        List<T>[] lists;
        Map<K, T> map;
        List<String> fixed;
    }

    static class Bound extends Base<Integer, String> implements Source<Long> {
    }

    /** Leaves {@code K} unbound. */
    static class Half<K> extends Base<Integer, K> {
    }

    /** The JDK's own view of {@code List<Integer>} and {@code Map<String, Integer>}. */
    static class Expected {
        List<Integer> list;
        Map<String, Integer> map;
    }

    private static Type field(Class<?> type, String name) throws NoSuchFieldException {
        return type.getDeclaredField(name).getGenericType();
    }

    @Test
    void a_bound_variable_is_replaced_by_its_binding() throws Exception {
        assertEquals(Integer.class, GenericTypes.resolve(field(Base.class, "one"), Bound.class));
        assertEquals(Integer[].class, GenericTypes.resolve(field(Base.class, "many"), Bound.class));
        assertEquals(Long.class, GenericTypes.resolve(Source.class.getTypeParameters()[0], Bound.class),
                "an interface's variable is bound too");
    }

    @Test
    void a_resolved_parameterized_type_equals_the_jdk_one() throws Exception {
        Type map = GenericTypes.resolve(field(Base.class, "map"), Bound.class);
        Type expected = field(Expected.class, "map");

        assertEquals(expected, map);
        assertEquals(map, expected);
        assertEquals(expected.hashCode(), map.hashCode());
        assertEquals(expected.getTypeName(), map.getTypeName());
        assertEquals(map.getTypeName(), map.toString());
        assertEquals(List.of(String.class, Integer.class),
                List.of(((ParameterizedType) map).getActualTypeArguments()));
        assertNotEquals(field(Expected.class, "list"), map);
    }

    @Test
    void a_generic_array_of_a_resolved_type_stays_a_generic_array() throws Exception {
        GenericArrayType lists = assertInstanceOf(GenericArrayType.class,
                GenericTypes.resolve(field(Base.class, "lists"), Bound.class));

        assertEquals(field(Expected.class, "list"), lists.getGenericComponentType());
        assertEquals("java.util.List<java.lang.Integer>[]", lists.getTypeName());
        assertEquals(lists.getTypeName(), lists.toString());
        assertEquals(lists, GenericTypes.resolve(field(Base.class, "lists"), Bound.class));
        assertEquals(lists.hashCode(), GenericTypes.resolve(field(Base.class, "lists"), Bound.class).hashCode());
    }

    @Test
    void what_nothing_binds_is_left_as_it_is() throws Exception {
        Type map = GenericTypes.resolve(field(Base.class, "map"), Half.class);
        assertInstanceOf(TypeVariable.class, ((ParameterizedType) map).getActualTypeArguments()[0]);

        Type fixed = field(Base.class, "fixed");
        assertSame(fixed, GenericTypes.resolve(fixed, Bound.class));
        assertSame(fixed, GenericTypes.resolve(fixed, String.class), "a class without bindings");
    }
}
