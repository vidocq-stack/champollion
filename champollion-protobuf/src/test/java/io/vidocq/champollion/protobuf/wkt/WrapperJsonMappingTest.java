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
package io.vidocq.champollion.protobuf.wkt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufJson;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The wrapper well-known types in the proto3 JSON mapping: the bare JSON form of the wrapped value, inside a message
 * as well as at the top level, with the unsigned wrappers written and read as unsigned numbers.
 */
class WrapperJsonMappingTest {

    private static final String UINT64_MAX = "18446744073709551615";
    private static final String UINT32_MAX = "4294967295";

    @ProtobufMessage("test.WrapperFields")
    public record WrapperFields(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Wrappers.DoubleValue d,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) Wrappers.FloatValue f,
            @ProtobufField(number = 3, type = FieldType.MESSAGE) Wrappers.Int64Value i64,
            @ProtobufField(number = 4, type = FieldType.MESSAGE) Wrappers.UInt64Value u64,
            @ProtobufField(number = 5, type = FieldType.MESSAGE) Wrappers.Int32Value i32,
            @ProtobufField(number = 6, type = FieldType.MESSAGE) Wrappers.UInt32Value u32,
            @ProtobufField(number = 7, type = FieldType.MESSAGE) Wrappers.BoolValue b,
            @ProtobufField(number = 8, type = FieldType.MESSAGE) Wrappers.StringValue s,
            @ProtobufField(number = 9, type = FieldType.MESSAGE) Wrappers.BytesValue bs
    ) implements Message {}

    @ProtobufMessage("test.WrapperLists")
    public record WrapperLists(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) List<Wrappers.DoubleValue> doubles,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) List<Wrappers.Int64Value> longs,
            @ProtobufField(number = 3, type = FieldType.MESSAGE) List<Wrappers.Int32Value> ints,
            @ProtobufField(number = 4, type = FieldType.MESSAGE) List<Wrappers.BoolValue> bools,
            @ProtobufField(number = 5, type = FieldType.MESSAGE) List<Wrappers.StringValue> strings,
            @ProtobufField(number = 6, type = FieldType.MESSAGE) List<Wrappers.BytesValue> bytes
    ) implements Message {}

    private static WrapperFields fields() {
        return new WrapperFields(new Wrappers.DoubleValue(1.5), new Wrappers.FloatValue(Float.NaN),
                new Wrappers.Int64Value(-2L), new Wrappers.UInt64Value(3L), new Wrappers.Int32Value(-4),
                new Wrappers.UInt32Value(5), new Wrappers.BoolValue(true), new Wrappers.StringValue("x"),
                new Wrappers.BytesValue(new byte[] {1}));
    }

    private static final String FIELDS_JSON = "{\"d\":1.5,\"f\":\"NaN\",\"i64\":\"-2\",\"u64\":\"3\",\"i32\":-4,"
            + "\"u32\":5,\"b\":true,\"s\":\"x\",\"bs\":\"AQ==\"}";

    @Test
    void wrapper_fields_are_written_as_their_bare_value() {
        assertEquals(FIELDS_JSON, ProtobufJson.toJson(fields()));
    }

    @Test
    void wrapper_fields_are_read_from_their_bare_value() {
        assertEquals(fields(), ProtobufJson.fromJson(WrapperFields.class, FIELDS_JSON));
    }

    @Test
    void repeated_wrappers_are_arrays_of_bare_values() {
        WrapperLists lists = new WrapperLists(List.of(new Wrappers.DoubleValue(Double.NEGATIVE_INFINITY)),
                List.of(new Wrappers.Int64Value(7L)), List.of(new Wrappers.Int32Value(8)),
                List.of(new Wrappers.BoolValue(false)), List.of(new Wrappers.StringValue("y")),
                List.of(new Wrappers.BytesValue(new byte[] {2})));
        String json = ProtobufJson.toJson(lists);
        assertEquals("{\"doubles\":[\"-Infinity\"],\"longs\":[\"7\"],\"ints\":[8],\"bools\":[false],"
                + "\"strings\":[\"y\"],\"bytes\":[\"Ag==\"]}", json);
        assertEquals(lists, ProtobufJson.fromJson(WrapperLists.class, json));
    }

    @Test
    void special_floating_values_of_top_level_wrappers_are_strings() {
        assertEquals("\"Infinity\"", ProtobufJson.toJson(new Wrappers.DoubleValue(Double.POSITIVE_INFINITY)));
        assertEquals("\"NaN\"", ProtobufJson.toJson(new Wrappers.DoubleValue(Double.NaN)));
        assertEquals("\"-Infinity\"", ProtobufJson.toJson(new Wrappers.FloatValue(Float.NEGATIVE_INFINITY)));
        assertEquals("\"Infinity\"", ProtobufJson.toJson(new Wrappers.FloatValue(Float.POSITIVE_INFINITY)));
    }

    @Test
    void top_level_wrappers_are_read_from_their_bare_value() {
        assertEquals(new Wrappers.DoubleValue(Double.POSITIVE_INFINITY),
                ProtobufJson.fromJson(Wrappers.DoubleValue.class, "\"Infinity\""));
        assertEquals(new Wrappers.FloatValue(2.5f), ProtobufJson.fromJson(Wrappers.FloatValue.class, "2.5"));
        assertEquals(new Wrappers.Int64Value(-9L), ProtobufJson.fromJson(Wrappers.Int64Value.class, "\"-9\""));
        assertEquals(new Wrappers.Int64Value(9L), ProtobufJson.fromJson(Wrappers.Int64Value.class, "9"));
        assertEquals(new Wrappers.Int32Value(6), ProtobufJson.fromJson(Wrappers.Int32Value.class, "\"6\""));
        assertEquals(new Wrappers.BoolValue(false), ProtobufJson.fromJson(Wrappers.BoolValue.class, "false"));
        assertEquals(new Wrappers.StringValue("z"), ProtobufJson.fromJson(Wrappers.StringValue.class, "\"z\""));
        assertEquals(new Wrappers.BytesValue(new byte[] {1, 2}),
                ProtobufJson.fromJson(Wrappers.BytesValue.class, "\"AQI=\""));
    }

    @ParameterizedTest
    @ValueSource(classes = {
            Wrappers.DoubleValue.class, Wrappers.Int64Value.class, Wrappers.UInt64Value.class,
            Wrappers.Int32Value.class, Wrappers.UInt32Value.class, Wrappers.BoolValue.class,
            Wrappers.StringValue.class, Wrappers.BytesValue.class, Timestamp.class, Duration.class,
            FieldMask.class, Any.class
    })
    void a_wrapper_or_well_known_type_rejects_a_json_value_of_the_wrong_kind(Class<?> type) {
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(type, "[]"));
    }

    @Test
    void uint64_value_is_written_unsigned() {
        assertEquals("\"" + UINT64_MAX + "\"", ProtobufJson.toJson(new Wrappers.UInt64Value(-1L)));
    }

    @Test
    void uint64_value_field_is_written_unsigned() {
        WrapperFields f = new WrapperFields(null, null, null, new Wrappers.UInt64Value(-1L), null, null, null, null,
                null);
        assertEquals("{\"u64\":\"" + UINT64_MAX + "\"}", ProtobufJson.toJson(f));
    }

    @Test
    void uint64_value_is_read_unsigned() {
        assertEquals(new Wrappers.UInt64Value(-1L),
                ProtobufJson.fromJson(Wrappers.UInt64Value.class, "\"" + UINT64_MAX + "\""));
        assertEquals(new Wrappers.UInt64Value(-1L), ProtobufJson.fromJson(Wrappers.UInt64Value.class, UINT64_MAX));
    }

    @Test
    void uint64_value_rejects_negative_and_too_large_numbers() {
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Wrappers.UInt64Value.class, "-1"));
        assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(Wrappers.UInt64Value.class, "18446744073709551616"));
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Wrappers.UInt64Value.class, "1.5"));
    }

    @Test
    void uint32_value_is_written_unsigned() {
        assertEquals(UINT32_MAX, ProtobufJson.toJson(new Wrappers.UInt32Value(-1)));
        WrapperFields f = new WrapperFields(null, null, null, null, null, new Wrappers.UInt32Value(-1), null, null,
                null);
        assertEquals("{\"u32\":" + UINT32_MAX + "}", ProtobufJson.toJson(f));
    }

    @Test
    void uint32_value_is_read_unsigned() {
        assertEquals(new Wrappers.UInt32Value(-1), ProtobufJson.fromJson(Wrappers.UInt32Value.class, UINT32_MAX));
        assertEquals(new Wrappers.UInt32Value(-1),
                ProtobufJson.fromJson(Wrappers.UInt32Value.class, "\"" + UINT32_MAX + "\""));
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Wrappers.UInt32Value.class, "-1"));
    }
}
