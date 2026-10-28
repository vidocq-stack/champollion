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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Empty;
import jakarta.json.JsonException;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Proto3 JSON mapping of scalar fields, singular and repeated: canonical output, the lenient inputs the mapping
 * accepts, and the inputs it rejects.
 */
class JsonScalarMappingTest {

    public enum Level { LOW, HIGH }

    @ProtobufMessage
    public record Lists(
            @ProtobufField(number = 1, type = FieldType.INT32) List<Integer> int32s,
            @ProtobufField(number = 2, type = FieldType.UINT32) List<Integer> uint32s,
            @ProtobufField(number = 3, type = FieldType.INT64) List<Long> int64s,
            @ProtobufField(number = 4, type = FieldType.UINT64) List<Long> uint64s,
            @ProtobufField(number = 5, type = FieldType.BOOL) List<Boolean> bools,
            @ProtobufField(number = 6, type = FieldType.FLOAT) List<Float> floats,
            @ProtobufField(number = 7, type = FieldType.DOUBLE) List<Double> doubles,
            @ProtobufField(number = 8, type = FieldType.STRING) List<String> strings,
            @ProtobufField(number = 9, type = FieldType.BYTES) List<byte[]> bytes,
            @ProtobufField(number = 10, type = FieldType.ENUM) List<Level> levels
    ) implements Message {}

    @ProtobufMessage
    public record Singles(
            @ProtobufField(number = 1, type = FieldType.INT32) int int32,
            @ProtobufField(number = 2, type = FieldType.UINT32) int uint32,
            @ProtobufField(number = 3, type = FieldType.INT64) long int64,
            @ProtobufField(number = 4, type = FieldType.UINT64) long uint64,
            @ProtobufField(number = 5, type = FieldType.BOOL) boolean bool,
            @ProtobufField(number = 6, type = FieldType.FLOAT) float flt,
            @ProtobufField(number = 7, type = FieldType.DOUBLE) double dbl,
            @ProtobufField(number = 8, type = FieldType.STRING) String str,
            @ProtobufField(number = 9, type = FieldType.BYTES) byte[] bin,
            @ProtobufField(number = 10, type = FieldType.ENUM) Level level,
            @ProtobufField(number = 11, type = FieldType.MESSAGE) Empty empty
    ) implements Message {}

    @ProtobufMessage
    public record Outer(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Lists lists,
            @ProtobufField(number = 2, type = FieldType.INT32) int after
    ) implements Message {}

    private static Lists lists() {
        return new Lists(List.of(-1, 2), List.of(-1), List.of(-5L), List.of(-1L), List.of(true, false),
                List.of(1.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
                List.of(2.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
                List.of("a"), List.of(new byte[] {1}), List.of(Level.HIGH, Level.LOW));
    }

    private static final String LISTS_JSON = "{\"int32s\":[-1,2],\"uint32s\":[4294967295],\"int64s\":[\"-5\"],"
            + "\"uint64s\":[\"18446744073709551615\"],\"bools\":[true,false],"
            + "\"floats\":[1.5,\"NaN\",\"Infinity\",\"-Infinity\"],\"doubles\":[2.5,\"NaN\",\"Infinity\",\"-Infinity\"],"
            + "\"strings\":[\"a\"],\"bytes\":[\"AQ==\"],\"levels\":[\"HIGH\",\"LOW\"]}";

    private static void assertListsEqual(Lists expected, Lists actual) {
        assertEquals(expected.int32s(), actual.int32s());
        assertEquals(expected.uint32s(), actual.uint32s());
        assertEquals(expected.int64s(), actual.int64s());
        assertEquals(expected.uint64s(), actual.uint64s());
        assertEquals(expected.bools(), actual.bools());
        assertEquals(expected.floats(), actual.floats());
        assertEquals(expected.doubles(), actual.doubles());
        assertEquals(expected.strings(), actual.strings());
        assertArrayEquals(expected.bytes().get(0), actual.bytes().get(0));
        assertEquals(expected.levels(), actual.levels());
    }

    @Test
    void repeated_scalars_are_written_canonically() {
        assertEquals(LISTS_JSON, ProtobufJson.toJson(lists()));
    }

    @Test
    void repeated_scalars_are_read_back() {
        assertListsEqual(lists(), ProtobufJson.fromJson(Lists.class, LISTS_JSON));
    }

    @Test
    void repeated_scalars_of_a_nested_message_are_written_inside_it() {
        Outer o = new Outer(lists(), 3);
        String json = ProtobufJson.toJson(o);
        assertEquals("{\"lists\":" + LISTS_JSON + ",\"after\":3}", json);
        Outer back = ProtobufJson.fromJson(Outer.class, json);
        assertListsEqual(lists(), back.lists());
        assertEquals(3, back.after());
    }

    @Test
    void special_floating_values_of_singular_fields_are_strings() {
        Singles s = new Singles(0, 0, 0, 0, false, Float.NEGATIVE_INFINITY, Double.NaN, "", new byte[0], Level.LOW, null);
        String json = ProtobufJson.toJson(s);
        assertEquals("{\"flt\":\"-Infinity\",\"dbl\":\"NaN\"}", json);
        Singles back = ProtobufJson.fromJson(Singles.class, json);
        assertEquals(Float.NEGATIVE_INFINITY, back.flt());
        assertTrue(Double.isNaN(back.dbl()));

        Singles inf = new Singles(0, 0, 0, 0, false, Float.NaN, Double.POSITIVE_INFINITY, "", new byte[0], Level.LOW, null);
        assertEquals("{\"flt\":\"NaN\",\"dbl\":\"Infinity\"}", ProtobufJson.toJson(inf));
        Singles neg = new Singles(0, 0, 0, 0, false, Float.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "", new byte[0],
                Level.LOW, null);
        assertEquals("{\"flt\":\"Infinity\",\"dbl\":\"-Infinity\"}", ProtobufJson.toJson(neg));
    }

    @Test
    void an_empty_message_field_is_an_empty_object() {
        Singles s = new Singles(0, 0, 0, 0, false, 0f, 0.0, "", new byte[0], Level.LOW, Empty.INSTANCE);
        assertEquals("{\"empty\":{}}", ProtobufJson.toJson(s));
        assertSame(Empty.INSTANCE, ProtobufJson.fromJson(Singles.class, "{\"empty\":{\"ignored\":1}}").empty());
    }

    @Test
    void numbers_are_accepted_as_strings_and_with_an_exponent() {
        Singles s = ProtobufJson.fromJson(Singles.class,
                "{\"int32\":\"1e2\",\"uint32\":\"7\",\"int64\":1.0,\"uint64\":\"18446744073709551615\","
                        + "\"flt\":\"1.5\",\"dbl\":\"-2.5\"}");
        assertEquals(100, s.int32());
        assertEquals(7, s.uint32());
        assertEquals(1L, s.int64());
        assertEquals(-1L, s.uint64());
        assertEquals(1.5f, s.flt());
        assertEquals(-2.5, s.dbl());
    }

    @Test
    void enum_accepts_its_number_and_null() {
        Singles s = ProtobufJson.fromJson(Singles.class, "{\"level\":1}");
        assertEquals(Level.HIGH, s.level());
        Lists l = ProtobufJson.fromJson(Lists.class, "{\"levels\":[1,0]}");
        assertEquals(List.of(Level.HIGH, Level.LOW), l.levels());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"int32\":2147483648}", "{\"int32\":1.5}", "{\"int32\":\"x\"}", "{\"int32\":true}", "{\"int32\":1e30}",
            "{\"uint32\":-1}", "{\"uint32\":4294967296}", "{\"uint32\":0.5}",
            "{\"int64\":\"9223372036854775808\"}", "{\"int64\":\"0.5\"}",
            "{\"uint64\":-1}", "{\"uint64\":\"18446744073709551616\"}", "{\"uint64\":0.5}",
            "{\"bool\":\"true\"}", "{\"flt\":3.5e38}", "{\"flt\":true}", "{\"dbl\":1e309}",
            "{\"str\":1}", "{\"bin\":1}", "{\"level\":true}", "{\"empty\":1}",
            "{\"lists\":1}", "{\"lists\":{\"int32s\":1}}"
    })
    void invalid_values_are_rejected(String json) {
        Class<?> type = json.contains("lists") ? Outer.class : Singles.class;
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(type, json));
    }

    @Test
    void unknown_fields_are_skipped_whatever_their_shape() {
        Singles s = ProtobufJson.fromJson(Singles.class,
                "{\"x\":{\"a\":[1,{\"b\":[2]}],\"c\":{}},\"y\":[[],{}],\"z\":\"s\",\"int32\":4}");
        assertEquals(4, s.int32());
    }

    @Test
    void fields_may_come_in_any_order() {
        Outer o = ProtobufJson.fromJson(Outer.class, "{\"after\":2,\"lists\":{\"int32s\":[1]}}");
        assertEquals(2, o.after());
        assertEquals(List.of(1), o.lists().int32s());
    }

    @Test
    void the_document_must_be_a_non_empty_object() {
        assertThrows(JsonException.class, () -> ProtobufJson.fromJson(Singles.class, ""));
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Singles.class, "[]"));
    }

    @Test
    void empty_reads_from_any_object() {
        assertSame(Empty.INSTANCE, ProtobufJson.fromJson(Empty.class, "{\"a\":1}"));
        assertEquals("{}", ProtobufJson.toJson(Empty.INSTANCE));
    }

    @Test
    void only_records_can_be_written() {
        Object notARecord = new Object();
        assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(notARecord));
    }

    @Test
    void writer_and_stream_outputs_match_the_string_output() {
        Outer o = new Outer(lists(), 1);
        StringWriter sw = new StringWriter();
        ProtobufJson.toJson(o, sw);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtobufJson.toJson(o, out);
        String expected = ProtobufJson.toJson(o);
        assertEquals(expected, sw.toString());
        assertEquals(expected, out.toString(StandardCharsets.UTF_8));
    }
}
