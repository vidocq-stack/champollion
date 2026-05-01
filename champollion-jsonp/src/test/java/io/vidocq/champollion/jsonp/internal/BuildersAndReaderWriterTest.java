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
            assertThrows(NullPointerException.class,
                    () -> new ChampollionJsonObjectBuilder().add(null, 1));
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
