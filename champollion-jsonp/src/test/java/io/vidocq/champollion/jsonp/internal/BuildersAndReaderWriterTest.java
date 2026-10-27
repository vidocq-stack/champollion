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
package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildersAndReaderWriterTest {

    @Nested
    @DisplayName("ChampollionJsonObjectBuilder")
    class ObjectBuilder {

        @Test
        void empty_object() {
            var o = new ChampollionJsonObjectBuilder().build();
            assertEquals("{}", o.toString());
        }

        @Test
        void members_preserve_insertion_order() {
            var o = new ChampollionJsonObjectBuilder()
                    .add("a", 1)
                    .add("b", "x")
                    .add("c", true)
                    .addNull("d")
                    .build();
            assertEquals("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null}", o.toString());
        }

        @Test
        void nested_via_builders() {
            var o = new ChampollionJsonObjectBuilder()
                    .add("a", new ChampollionJsonObjectBuilder().add("b", 1))
                    .add("c", new ChampollionJsonArrayBuilder().add(1).add(2))
                    .build();
            assertEquals("{\"a\":{\"b\":1},\"c\":[1,2]}", o.toString());
        }

        @Test
        void rejects_null_key() {
            var builder = new ChampollionJsonObjectBuilder();
            assertThrows(NullPointerException.class, () -> builder.add(null, 1));
        }

        @Test
        void addAll_copies_the_members_of_another_builder() {
            var o = new ChampollionJsonObjectBuilder()
                    .add("a", 1)
                    .addAll(new ChampollionJsonObjectBuilder().add("b", "x").add("a", 2))
                    .build();
            assertEquals("{\"a\":2,\"b\":\"x\"}", o.toString());
        }
    }

    @Nested
    @DisplayName("ChampollionJsonArrayBuilder")
    class ArrayBuilder {

        @Test
        void empty_array() {
            var a = new ChampollionJsonArrayBuilder().build();
            assertEquals("[]", a.toString());
        }

        @Test
        void all_value_types() {
            var a = new ChampollionJsonArrayBuilder()
                    .add(1)
                    .add(2L)
                    .add(3.14)
                    .add("x")
                    .add(true)
                    .add(false)
                    .addNull()
                    .build();
            assertEquals("[1,2,3.14,\"x\",true,false,null]", a.toString());
        }

        @Test
        void nested_arrays_and_objects() {
            var a = new ChampollionJsonArrayBuilder()
                    .add(new ChampollionJsonObjectBuilder().add("x", 1))
                    .add(new ChampollionJsonArrayBuilder().add("y"))
                    .build();
            assertEquals("[{\"x\":1},[\"y\"]]", a.toString());
        }

        @Test
        void inserts_every_value_type_at_an_index() {
            // JSON-P §4.8: add(index, value) inserts and shifts the following elements.
            var a = new ChampollionJsonArrayBuilder()
                    .add("end")
                    .add(0, new ChampollionJsonArrayBuilder().add(0))
                    .add(0, new ChampollionJsonObjectBuilder().add("k", 1))
                    .addNull(0)
                    .add(0, false)
                    .add(0, 2.5)
                    .add(0, 4L)
                    .add(0, 3)
                    .add(0, BigInteger.TWO)
                    .add(0, new BigDecimal("1.5"))
                    .add(0, "s")
                    .build();
            assertEquals("[\"s\",1.5,2,3,4,2.5,false,null,{\"k\":1},[0],\"end\"]", a.toString());
        }

        @Test
        void replaces_every_value_type_at_an_index() {
            var builder = new ChampollionJsonArrayBuilder();
            for (int i = 0; i < 10; i++) builder.addNull();
            var a = builder
                    .set(0, "s")
                    .set(1, new BigDecimal("1.5"))
                    .set(2, BigInteger.TWO)
                    .set(3, 3)
                    .set(4, 4L)
                    .set(5, 2.5)
                    .set(6, true)
                    .set(7, new ChampollionJsonObjectBuilder().add("k", 1))
                    .set(8, new ChampollionJsonArrayBuilder().add(0))
                    .setNull(9)
                    .build();
            assertEquals("[\"s\",1.5,2,3,4,2.5,true,{\"k\":1},[0],null]", a.toString());
        }

        @Test
        void indexed_double_rejects_nan_and_infinity() {
            var builder = new ChampollionJsonArrayBuilder().add(1);
            assertThrows(NumberFormatException.class, () -> builder.add(0, Double.NaN));
            assertThrows(NumberFormatException.class, () -> builder.set(0, Double.POSITIVE_INFINITY));
        }
    }

    @Nested
    @DisplayName("ChampollionJsonReader — reads object model from text")
    class Reader {

        @Test
        void reads_empty_object() {
            JsonObject o = (JsonObject) read("{}");
            assertEquals(0, o.size());
        }

        @Test
        void reads_simple_object() {
            JsonObject o = (JsonObject) read("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null}");
            assertEquals(1, o.getInt("a"));
            assertEquals("x", o.getString("b"));
            assertSame(JsonValue.TRUE, o.get("c"));
            assertSame(JsonValue.NULL, o.get("d"));
        }

        @Test
        void reads_array() {
            JsonArray a = (JsonArray) read("[1,2,3]");
            assertEquals(List.of(1, 2, 3), List.of(a.getInt(0), a.getInt(1), a.getInt(2)));
        }

        @Test
        void reads_nested_structure() {
            JsonObject o = (JsonObject) read("{\"a\":{\"b\":[1,2]}}");
            JsonObject inner = o.getJsonObject("a");
            JsonArray arr = inner.getJsonArray("b");
            assertEquals(2, arr.size());
        }

        @Test
        void reads_root_primitive_string() {
            JsonValue v = read("\"hello\"");
            assertEquals(JsonValue.ValueType.STRING, v.getValueType());
        }

        @Test
        void reads_root_primitive_number() {
            JsonValue v = read("42");
            assertEquals(JsonValue.ValueType.NUMBER, v.getValueType());
        }

        @Test
        void rejects_malformed_input() {
            assertThrows(JsonException.class, () -> read("{"));
            assertThrows(JsonException.class, () -> read("[1,]"));
        }

        private JsonValue read(String s) {
            try (var r = new ChampollionJsonReader(new StringReader(s))) {
                return r.readValue();
            }
        }
    }

    @Nested
    @DisplayName("ChampollionJsonWriter — writes object model to text")
    class Writer {

        @Test
        void writes_empty_object() {
            var sw = new StringWriter();
            try (var w = new ChampollionJsonWriter(sw, /*pretty*/ false)) {
                w.write(new ChampollionJsonObjectBuilder().build());
            }
            assertEquals("{}", sw.toString());
        }

        @Test
        void writeArray_and_writeObject_emit_the_structure() {
            var arrayOut = new StringWriter();
            try (var w = new ChampollionJsonWriter(arrayOut, /*pretty*/ false)) {
                w.writeArray(new ChampollionJsonArrayBuilder().add(1).add("x").build());
            }
            assertEquals("[1,\"x\"]", arrayOut.toString());
            var objectOut = new StringWriter();
            try (var w = new ChampollionJsonWriter(objectOut, /*pretty*/ false)) {
                w.writeObject(new ChampollionJsonObjectBuilder().add("a", true).build());
            }
            assertEquals("{\"a\":true}", objectOut.toString());
        }

        @Test
        void close_without_write_is_silent() {
            // JSON-P §3.5: closing a writer that never wrote must not fail.
            var sw = new StringWriter();
            var w = new ChampollionJsonWriter(sw, /*pretty*/ false);
            w.close();
            assertEquals("", sw.toString());
        }

        @Test
        void writes_complex_object() {
            var sw = new StringWriter();
            var obj = new ChampollionJsonObjectBuilder()
                    .add("a", 1)
                    .add("b", new ChampollionJsonArrayBuilder().add("x").addNull())
                    .build();
            try (var w = new ChampollionJsonWriter(sw, /*pretty*/ false)) {
                w.write(obj);
            }
            assertEquals("{\"a\":1,\"b\":[\"x\",null]}", sw.toString());
        }

        @Test
        void roundtrip_via_reader_and_writer() {
            String input = "{\"a\":1,\"b\":[true,null,\"x\"],\"c\":3.14}";
            JsonValue v;
            try (var r = new ChampollionJsonReader(new StringReader(input))) {
                v = r.readValue();
            }
            var sw = new StringWriter();
            try (var w = new ChampollionJsonWriter(sw, /*pretty*/ false)) {
                w.write((jakarta.json.JsonStructure) v);
            }
            assertEquals(input, sw.toString());
        }
    }
}
