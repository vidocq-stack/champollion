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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.config.BinaryDataStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arrays, collections, maps and untyped values through the runtime registries (JSON-B 3.0 §3.11, §3.3.1). */
@DisplayName("JSON-B — arrays, collections, maps and untyped values")
class JsonbArraysAndContainersTest {

    private static final String NUMBERS = "[1,2,3]";
    private static final String LETTERS = "[\"b\",\"a\",\"c\"]";
    private static final String MAP_JSON = "{\"b\":1,\"a\":2}";

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public record PrimitiveArrays(int[] ints, long[] longs, double[] doubles, boolean[] booleans, short[] shorts,
                                  float[] floats, char[] chars, byte[] bytes, String[] strings) {}

    public record Collections(LinkedList<Integer> linked, ArrayDeque<Integer> arrayDeque,
                              PriorityQueue<Integer> priority, PriorityBlockingQueue<Integer> blocking,
                              TreeSet<String> treeSet, HashSet<String> hashSet, LinkedHashSet<String> linkedSet,
                              SortedSet<String> sortedSet, NavigableSet<String> navigableSet, Set<String> set,
                              Deque<Integer> deque, Queue<Integer> queue, Collection<Integer> collection) {}

    public record Maps(TreeMap<String, Integer> tree, SortedMap<String, Integer> sorted,
                       NavigableMap<String, Integer> navigable, ConcurrentHashMap<String, Integer> concurrent,
                       HashMap<String, Integer> hash, Map<String, List<Integer>> nested) {}

    @SuppressWarnings("rawtypes")
    public record RawContainers(List list, Map map, Optional optional, Object any) {}

    public record GenericArray(List<String>[] lists) {}

    @Test
    void primitive_arrays_round_trip() {
        var in = new PrimitiveArrays(new int[]{1, -2}, new long[]{3L, 1L << 40}, new double[]{1.5, -2.25},
                new boolean[]{true, false}, new short[]{7, -8}, new float[]{0.5f, 1.25f}, new char[]{'x', 'y'},
                new byte[]{-1, 2}, new String[]{"a", null});
        String json = jsonb.toJson(in);
        assertEquals("{\"ints\":[1,-2],\"longs\":[3,1099511627776],\"doubles\":[1.5,-2.25],"
                + "\"booleans\":[true,false],\"shorts\":[7,-8],\"floats\":[0.5,1.25],\"chars\":[\"x\",\"y\"],"
                + "\"bytes\":[-1,2],\"strings\":[\"a\",null]}", json);
        var out = jsonb.fromJson(json, PrimitiveArrays.class);
        assertArrayEquals(in.ints(), out.ints());
        assertArrayEquals(in.longs(), out.longs());
        assertArrayEquals(in.doubles(), out.doubles());
        assertArrayEquals(in.booleans(), out.booleans());
        assertArrayEquals(in.shorts(), out.shorts());
        assertArrayEquals(in.floats(), out.floats());
        assertArrayEquals(in.chars(), out.chars());
        assertArrayEquals(in.bytes(), out.bytes());
        assertArrayEquals(in.strings(), out.strings());
    }

    @Test
    void primitive_arrays_read_null() {
        String json = "{\"ints\":null,\"longs\":null,\"doubles\":null,\"booleans\":null,\"shorts\":null,"
                + "\"floats\":null,\"chars\":null,\"bytes\":null,\"strings\":null}";
        var out = jsonb.fromJson(json, PrimitiveArrays.class);
        assertNull(out.ints());
        assertNull(out.longs());
        assertNull(out.doubles());
        assertNull(out.booleans());
        assertNull(out.shorts());
        assertNull(out.floats());
        assertNull(out.chars());
        assertNull(out.bytes());
        assertNull(out.strings());
    }

    @Test
    void an_empty_string_char_element_reads_as_nul() {
        assertArrayEquals(new char[]{'\0'}, jsonb.fromJson("{\"chars\":[\"\"]}", PrimitiveArrays.class).chars());
    }

    @Test
    void an_array_member_rejects_an_object() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"ints\":{}}", PrimitiveArrays.class));
    }

    @Test
    void root_primitive_arrays() {
        assertArrayEquals(new int[]{1, 2, 3}, jsonb.fromJson(NUMBERS, int[].class));
        assertArrayEquals(new long[]{1, 2, 3}, jsonb.fromJson(NUMBERS, long[].class));
        assertEquals(NUMBERS, jsonb.toJson(new int[]{1, 2, 3}));
        assertEquals("[true]", jsonb.toJson(new boolean[]{true}));
        assertEquals("[\"c\"]", jsonb.toJson(new char[]{'c'}));
    }

    @Test
    void generic_array_round_trip() {
        @SuppressWarnings("unchecked")
        List<String>[] lists = new List[]{List.of("a"), List.of("b", "c")};
        String json = jsonb.toJson(new GenericArray(lists));
        assertEquals("{\"lists\":[[\"a\"],[\"b\",\"c\"]]}", json);
        var out = jsonb.fromJson(json, GenericArray.class);
        assertEquals(2, out.lists().length);
        assertEquals(List.of("b", "c"), out.lists()[1]);
    }

    @Test
    void byte_array_strategies() throws Exception {
        byte[] bytes = {(byte) 0xfb, (byte) 0xff, 0x01};
        try (var base64 = JsonbBuilder.create(new JsonbConfig().withBinaryDataStrategy(BinaryDataStrategy.BASE_64));
             var base64Url = JsonbBuilder.create(new JsonbConfig().withBinaryDataStrategy(BinaryDataStrategy.BASE_64_URL))) {
            assertEquals("\"+/8B\"", base64.toJson(bytes));
            assertEquals("\"-_8B\"", base64Url.toJson(bytes));
            assertArrayEquals(bytes, base64.fromJson("\"+/8B\"", byte[].class));
            assertArrayEquals(bytes, base64Url.fromJson("\"-_8B\"", byte[].class));
            assertNull(base64.fromJson("{\"bytes\":null}", PrimitiveArrays.class).bytes());
            assertNull(base64Url.fromJson("{\"bytes\":null}", PrimitiveArrays.class).bytes());
            assertThrows(JsonbException.class, () -> base64.fromJson("[1]", byte[].class));
            assertThrows(JsonbException.class, () -> base64Url.fromJson("[1]", byte[].class));
        }
    }

    @Test
    void collection_implementations_follow_the_declared_type() {
        String json = "{\"linked\":[3,1],\"arrayDeque\":[3,1],\"priority\":[3,1],\"blocking\":[3,1],"
                + "\"treeSet\":" + LETTERS + ",\"hashSet\":" + LETTERS + ",\"linkedSet\":" + LETTERS + ","
                + "\"sortedSet\":" + LETTERS + ",\"navigableSet\":" + LETTERS + ",\"set\":" + LETTERS + ","
                + "\"deque\":[3,1],\"queue\":[3,1],\"collection\":[3,1]}";
        var out = jsonb.fromJson(json, Collections.class);
        assertEquals(List.of(3, 1), new ArrayList<>(out.linked()));
        assertEquals(List.of(3, 1), new ArrayList<>(out.arrayDeque()));
        assertEquals(1, out.priority().peek());
        assertEquals(1, out.blocking().peek());
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(out.treeSet()));
        assertEquals(Set.of("a", "b", "c"), out.hashSet());
        assertEquals(List.of("b", "a", "c"), new ArrayList<>(out.linkedSet()));
        assertInstanceOf(TreeSet.class, out.sortedSet());
        assertInstanceOf(TreeSet.class, out.navigableSet());
        assertInstanceOf(LinkedHashSet.class, out.set());
        assertInstanceOf(ArrayDeque.class, out.deque());
        assertInstanceOf(LinkedList.class, out.queue());
        assertInstanceOf(ArrayList.class, out.collection());
        String back = jsonb.toJson(out);
        assertTrue(back.startsWith("{\"linked\":[3,1],\"arrayDeque\":[3,1],\"priority\":[1,3]"), back);
    }

    @Test
    void collections_read_null_and_reject_an_object() {
        assertNull(jsonb.fromJson("{\"linked\":null}", Collections.class).linked());
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"linked\":{}}", Collections.class));
    }

    @Test
    void map_implementations_follow_the_declared_type() {
        String json = "{\"tree\":" + MAP_JSON + ",\"sorted\":" + MAP_JSON + ",\"navigable\":" + MAP_JSON
                + ",\"concurrent\":" + MAP_JSON + ",\"hash\":" + MAP_JSON + ",\"nested\":{\"k\":[1,null]}}";
        var out = jsonb.fromJson(json, Maps.class);
        assertEquals(List.of("a", "b"), new ArrayList<>(out.tree().keySet()));
        assertInstanceOf(TreeMap.class, out.sorted());
        assertInstanceOf(TreeMap.class, out.navigable());
        assertInstanceOf(ConcurrentHashMap.class, out.concurrent());
        assertEquals(Map.of("a", 2, "b", 1), out.hash());
        assertEquals(java.util.Arrays.asList(1, null), out.nested().get("k"));
        assertEquals("{\"tree\":{\"a\":2,\"b\":1},\"sorted\":{\"a\":2,\"b\":1},\"navigable\":{\"a\":2,\"b\":1},"
                + "\"concurrent\":{\"a\":2,\"b\":1},\"hash\":{\"b\":1,\"a\":2},\"nested\":{\"k\":[1,null]}}",
                jsonb.toJson(out));
    }

    @Test
    void maps_read_null_and_reject_an_array() {
        assertNull(jsonb.fromJson("{\"tree\":null}", Maps.class).tree());
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"tree\":[]}", Maps.class));
    }

    @Test
    void a_null_map_value_is_written() {
        var map = new LinkedHashMap<String, Integer>();
        map.put("a", null);
        assertEquals("{\"a\":null}", jsonb.toJson(map));
    }

    @Test
    void a_non_string_map_key_is_rejected() {
        var map = Map.of(1, "one");
        assertThrows(JsonbException.class, () -> jsonb.toJson(map));
    }

    @Test
    void raw_containers_hold_untyped_values() {
        String json = "{\"list\":[1,\"x\",true,false,null,2.5,{\"k\":[]}],\"map\":{\"big\":12345678901,"
                + "\"huge\":123456789012345678901234567890},\"optional\":\"v\",\"any\":[[1]]}";
        var out = jsonb.fromJson(json, RawContainers.class);
        var nested = new LinkedHashMap<String, Object>();
        nested.put("k", List.of());
        assertEquals(java.util.Arrays.asList(1, "x", true, false, null, new BigDecimal("2.5"), nested), out.list());
        assertEquals(12345678901L, ((BigInteger) out.map().get("big")).longValueExact());
        assertEquals(new BigInteger("123456789012345678901234567890"), out.map().get("huge"));
        assertEquals(Optional.of("v"), out.optional());
        assertEquals(List.of(List.of(1)), out.any());
    }

    @Test
    void raw_containers_are_written_by_runtime_type() {
        var in = new RawContainers(List.of(1, "x"), Map.of("k", true), Optional.of(2), List.of(3));
        assertEquals("{\"list\":[1,\"x\"],\"map\":{\"k\":true},\"optional\":2,\"any\":[3]}", jsonb.toJson(in));
        var empty = new RawContainers(null, null, Optional.empty(), null);
        assertEquals("{}", jsonb.toJson(empty));
    }

    @Test
    void untyped_root_values() {
        assertEquals(List.of(1, 2), jsonb.fromJson("[1,2]", Object.class));
        assertEquals(Map.of("a", "b"), jsonb.fromJson("{\"a\":\"b\"}", Object.class));
        assertNull(jsonb.fromJson("null", Object.class));
        assertEquals("[1,\"a\"]", jsonb.toJson(List.of(1, "a"), Object.class));
    }

    @Test
    void optional_root_values() throws Exception {
        var optionalOfString = Optional.class.getMethod("get").getDeclaringClass();
        assertEquals(Optional.empty(), jsonb.fromJson("null", optionalOfString));
        assertEquals(Optional.of(3), jsonb.fromJson("3", optionalOfString));
        assertEquals("null", jsonb.toJson(Optional.empty()));
        assertEquals("\"x\"", jsonb.toJson(Optional.of("x")));
    }
}
