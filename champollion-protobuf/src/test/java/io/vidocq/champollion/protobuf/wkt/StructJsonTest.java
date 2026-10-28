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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufJson;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code google.protobuf.Struct}, {@code Value}, {@code ListValue} and {@code NullValue}: their proto3 JSON form is
 * the bare JSON object, value, array and {@code null}, at the top level as well as inside a message.
 */
class StructJsonTest {

    private static final String NAME = "name";

    @ProtobufMessage("test.Holder")
    public record Holder(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Value value,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) Struct struct,
            @ProtobufField(number = 3, type = FieldType.MESSAGE) ListValue list,
            @ProtobufField(number = 4, type = FieldType.MESSAGE) List<Value> values
    ) implements Message {}

    private static Struct sample() {
        Map<String, Value> inner = new LinkedHashMap<>();
        inner.put("k", Value.ofString("v"));
        Map<String, Value> fields = new LinkedHashMap<>();
        fields.put("nothing", Value.ofNull());
        fields.put("number", Value.ofNumber(1.5));
        fields.put(NAME, Value.ofString("Ada"));
        fields.put("flag", Value.ofBool(true));
        fields.put("object", Value.ofStruct(new Struct(inner)));
        fields.put("array", Value.ofList(new ListValue(List.of(Value.ofNumber(2.5), Value.ofBool(false)))));
        return new Struct(fields);
    }

    private static final String SAMPLE_JSON = "{\"nothing\":null,\"number\":1.5,\"name\":\"Ada\",\"flag\":true,"
            + "\"object\":{\"k\":\"v\"},\"array\":[2.5,false]}";

    @Test
    void struct_is_written_as_a_bare_object() {
        assertEquals(SAMPLE_JSON, ProtobufJson.toJson(sample()));
    }

    @Test
    void struct_is_read_from_a_bare_object() {
        assertEquals(sample(), ProtobufJson.fromJson(Struct.class, SAMPLE_JSON));
    }

    @Test
    void struct_and_list_without_content_are_empty_object_and_array() {
        assertEquals("{}", ProtobufJson.toJson(new Struct(null)));
        assertEquals("[]", ProtobufJson.toJson(new ListValue(null)));
    }

    @Test
    void list_value_round_trips_as_a_bare_array() {
        String json = "[1.5,\"a\",true,false,null,{},[]]";
        ListValue list = ProtobufJson.fromJson(ListValue.class, json);
        assertEquals(List.of(Value.ofNumber(1.5), Value.ofString("a"), Value.ofBool(true), Value.ofBool(false),
                Value.ofNull(), Value.ofStruct(new Struct(Map.of())), Value.ofList(new ListValue(List.of()))),
                list.values());
        assertEquals(json, ProtobufJson.toJson(list));
    }

    @Test
    void value_at_top_level_is_the_bare_json_value() {
        assertEquals("\"x\"", ProtobufJson.toJson(Value.ofString("x")));
        assertEquals("2.5", ProtobufJson.toJson(Value.ofNumber(2.5)));
        assertEquals("true", ProtobufJson.toJson(Value.ofBool(true)));
        assertEquals("null", ProtobufJson.toJson(Value.ofNull()));
        assertEquals("{\"a\":1.5}", ProtobufJson.toJson(Value.ofStruct(new Struct(Map.of("a", Value.ofNumber(1.5))))));
        assertEquals("[\"b\"]", ProtobufJson.toJson(Value.ofList(new ListValue(List.of(Value.ofString("b"))))));
    }

    @Test
    void value_without_any_kind_is_written_as_null() {
        assertEquals("null", ProtobufJson.toJson(new Value(null, null, null, null, null, null)));
    }

    @Test
    void value_is_read_from_every_kind_of_json_value() {
        assertEquals(Value.ofString("x"), ProtobufJson.fromJson(Value.class, "\"x\""));
        assertEquals(Value.ofNumber(3.0), ProtobufJson.fromJson(Value.class, "3"));
        assertEquals(Value.ofNull(), ProtobufJson.fromJson(Value.class, "null"));
        assertEquals(Value.ofBool(false), ProtobufJson.fromJson(Value.class, "false"));
    }

    @Test
    void null_value_is_read_from_json_null() {
        assertSame(NullValue.NULL_VALUE, ProtobufJson.fromJson(NullValue.class, "null"));
    }

    @Test
    void null_value_rejects_any_other_json_value() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(NullValue.class, "0"));
        assertEquals(IOException.class, e.getCause().getClass());
    }

    @Test
    void struct_requires_an_object_and_list_value_an_array() {
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Struct.class, "[]"));
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(ListValue.class, "{}"));
    }

    @Test
    void struct_value_and_list_inside_a_message_keep_their_bare_form() {
        Holder h = new Holder(Value.ofString("s"), new Struct(Map.of("n", Value.ofNumber(1.5))),
                new ListValue(List.of(Value.ofBool(true))), List.of(Value.ofString("a"), Value.ofNull()));
        String json = ProtobufJson.toJson(h);
        assertEquals("{\"value\":\"s\",\"struct\":{\"n\":1.5},\"list\":[true],\"values\":[\"a\",null]}", json);
        assertEquals(h, ProtobufJson.fromJson(Holder.class, json));
    }

    @Test
    void json_null_for_a_value_field_is_the_null_value_not_an_absent_field() {
        Holder h = ProtobufJson.fromJson(Holder.class, "{\"value\":null,\"struct\":null}");
        assertEquals(Value.ofNull(), h.value());
        assertNull(h.struct());
    }

    @Test
    void struct_round_trips_through_the_binary_format() throws IOException {
        Struct s = sample();
        byte[] bytes = Protobuf.toByteArray(s);
        assertEquals(s, Protobuf.parser(Struct.class).parseFrom(bytes));
    }

    @Test
    void struct_and_list_values_pack_into_any_with_a_value_member() {
        Any any = Any.pack(new Struct(Map.of(NAME, Value.ofString("x"))));
        String json = ProtobufJson.toJson(any);
        assertEquals("{\"@type\":\"type.googleapis.com/google.protobuf.Struct\",\"value\":{\"name\":\"x\"}}", json);
        assertEquals(any, ProtobufJson.fromJson(Any.class, json));
    }
}
