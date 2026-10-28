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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Timestamp;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proto3 JSON mapping of map fields: an object whose keys are the map keys as strings and whose values follow the
 * mapping of the value type.
 */
class MapJsonTest {

    private static final String KEY = "k";

    public enum Color { RED, GREEN }

    @ProtobufMessage
    public record Point(
            @ProtobufField(number = 1, type = FieldType.INT32) int x
    ) implements Message {}

    @ProtobufMessage
    public record Keys(
            @ProtobufField(number = 1, type = FieldType.MAP, mapKey = FieldType.BOOL, mapValue = FieldType.STRING)
            Map<Boolean, String> byBool,
            @ProtobufField(number = 2, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.INT32)
            Map<Integer, Integer> byInt32,
            @ProtobufField(number = 3, type = FieldType.MAP, mapKey = FieldType.UINT32, mapValue = FieldType.UINT32)
            Map<Integer, Integer> byUint32,
            @ProtobufField(number = 4, type = FieldType.MAP, mapKey = FieldType.INT64, mapValue = FieldType.INT64)
            Map<Long, Long> byInt64,
            @ProtobufField(number = 5, type = FieldType.MAP, mapKey = FieldType.UINT64, mapValue = FieldType.UINT64)
            Map<Long, Long> byUint64
    ) implements Message {}

    @ProtobufMessage
    public record Values(
            @ProtobufField(number = 1, type = FieldType.MAP, mapValue = FieldType.BOOL) Map<String, Boolean> bools,
            @ProtobufField(number = 2, type = FieldType.MAP, mapValue = FieldType.FLOAT) Map<String, Float> floats,
            @ProtobufField(number = 3, type = FieldType.MAP, mapValue = FieldType.DOUBLE) Map<String, Double> doubles,
            @ProtobufField(number = 4, type = FieldType.MAP, mapValue = FieldType.BYTES) Map<String, byte[]> bytes,
            @ProtobufField(number = 5, type = FieldType.MAP, mapValue = FieldType.ENUM) Map<String, Color> colors,
            @ProtobufField(number = 6, type = FieldType.MAP, mapValue = FieldType.MESSAGE) Map<String, Point> points,
            @ProtobufField(number = 7, type = FieldType.MAP, mapValue = FieldType.MESSAGE) Map<String, Timestamp> times,
            @ProtobufField(number = 8, type = FieldType.MAP, mapValue = FieldType.STRING) Map<String, String> strings
    ) implements Message {}

    private static Keys keys() {
        return new Keys(Map.of(true, "t"), Map.of(-1, -2), Map.of(-1, -1), Map.of(-3L, -4L), Map.of(-1L, -1L));
    }

    private static final String KEYS_JSON = "{\"byBool\":{\"true\":\"t\"},\"byInt32\":{\"-1\":-2},"
            + "\"byUint32\":{\"4294967295\":4294967295},\"byInt64\":{\"-3\":\"-4\"},"
            + "\"byUint64\":{\"18446744073709551615\":\"18446744073709551615\"}}";

    @Test
    void every_key_type_is_written_as_its_canonical_string() {
        assertEquals(KEYS_JSON, ProtobufJson.toJson(keys()));
    }

    @Test
    void every_key_type_is_read_back_from_its_string() {
        assertEquals(keys(), ProtobufJson.fromJson(Keys.class, KEYS_JSON));
    }

    @Test
    void quoted_integer_values_are_accepted() {
        Keys k = ProtobufJson.fromJson(Keys.class,
                "{\"byUint32\":{\"1\":\"4294967295\"},\"byInt64\":{\"2\":3},\"byUint64\":{\"4\":5}}");
        assertEquals(Map.of(1, -1), k.byUint32());
        assertEquals(Map.of(2L, 3L), k.byInt64());
        assertEquals(Map.of(4L, 5L), k.byUint64());
    }

    @Test
    void uint32_keys_and_values_outside_the_range_are_rejected() {
        assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(Keys.class, "{\"byUint32\":{\"-1\":1}}"));
        assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(Keys.class, "{\"byUint32\":{\"1\":4294967296}}"));
    }

    @Test
    void null_keys_are_written_as_the_default_key_of_their_type() {
        Map<Boolean, String> bools = new HashMap<>();
        bools.put(null, "b");
        Map<Integer, Integer> ints = new HashMap<>();
        ints.put(null, 1);
        Keys k = new Keys(bools, ints, Map.of(), Map.of(), Map.of());
        assertEquals("{\"byBool\":{\"false\":\"b\"},\"byInt32\":{\"0\":1}}", ProtobufJson.toJson(k));
    }

    @Test
    void null_string_key_is_written_as_the_empty_key() {
        Map<String, String> strings = new HashMap<>();
        strings.put(null, "v");
        Values v = new Values(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), strings);
        assertEquals("{\"strings\":{\"\":\"v\"}}", ProtobufJson.toJson(v));
    }

    @Test
    void every_value_type_round_trips() {
        Values v = new Values(Map.of(KEY, true), Map.of(KEY, 1.5f), Map.of(KEY, 2.5), Map.of(KEY, new byte[] {1, 2}),
                Map.of(KEY, Color.GREEN), Map.of(KEY, new Point(7)), Map.of(), Map.of(KEY, "s"));
        String json = ProtobufJson.toJson(v);
        assertEquals("{\"bools\":{\"k\":true},\"floats\":{\"k\":1.5},\"doubles\":{\"k\":2.5},\"bytes\":{\"k\":\"AQI=\"},"
                + "\"colors\":{\"k\":\"GREEN\"},\"points\":{\"k\":{\"x\":7}},\"strings\":{\"k\":\"s\"}}", json);

        Values back = ProtobufJson.fromJson(Values.class, json);
        assertEquals(Map.of(KEY, true), back.bools());
        assertEquals(Map.of(KEY, 1.5f), back.floats());
        assertEquals(Map.of(KEY, 2.5), back.doubles());
        assertArrayEquals(new byte[] {1, 2}, back.bytes().get(KEY));
        assertEquals(Map.of(KEY, Color.GREEN), back.colors());
        assertEquals(Map.of(KEY, new Point(7)), back.points());
        assertEquals(Map.of(KEY, "s"), back.strings());
    }

    @Test
    void well_known_type_values_are_written_in_their_json_form() {
        Values v = new Values(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(KEY, new Timestamp(0, 0)), Map.of());
        assertEquals("{\"times\":{\"k\":\"1970-01-01T00:00:00Z\"}}", ProtobufJson.toJson(v));
    }

    @Test
    void null_values_are_written_and_read_as_json_null() {
        Map<String, String> strings = new HashMap<>();
        strings.put(KEY, null);
        Values v = new Values(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), strings);
        assertEquals("{\"strings\":{\"k\":null}}", ProtobufJson.toJson(v));
        Values back = ProtobufJson.fromJson(Values.class, "{\"strings\":{\"k\":null}}");
        assertTrue(back.strings().containsKey(KEY));
        assertNull(back.strings().get(KEY));
    }

    @Test
    void an_unknown_enum_name_in_a_map_value_reads_as_null() {
        Values back = ProtobufJson.fromJson(Values.class, "{\"colors\":{\"k\":\"BLUE\",\"n\":1}}");
        assertNull(back.colors().get(KEY));
        assertNull(back.colors().get("n"));
    }

    @Test
    void a_map_field_must_be_an_object() {
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Values.class, "{\"strings\":[]}"));
    }

    @Test
    void a_message_map_value_must_be_an_object() {
        assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(Values.class, "{\"points\":{\"k\":1}}"));
    }

    @Test
    void empty_maps_are_omitted() {
        Values v = new Values(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("{}", ProtobufJson.toJson(v));
        Values back = ProtobufJson.fromJson(Values.class, "{}");
        assertTrue(back.points().isEmpty());
    }
}
