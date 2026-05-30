package io.vidocq.champollion.jsonp.internal;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Stubs M2/M3 finis (parser.getObject/getArray/getValue, createObjectBuilder(Map), createDiff)")
class JsonProviderStubsTest {

    @Nested
    @DisplayName("JsonParser.getObject / getArray / getValue (Spec §3.10)")
    class ParserGetters {

        @Test
        void getObject_after_start_object() {
            try (JsonParser p = Json.createParser(new StringReader("{\"a\":1,\"b\":\"x\"}"))) {
                assertEquals(JsonParser.Event.START_OBJECT, p.next());
                JsonObject o = p.getObject();
                assertEquals(1, ((jakarta.json.JsonNumber) o.get("a")).intValue());
                assertEquals("x", ((jakarta.json.JsonString) o.get("b")).getString());
            }
        }

        @Test
        void getArray_after_start_array() {
            try (JsonParser p = Json.createParser(new StringReader("[1,2,3]"))) {
                assertEquals(JsonParser.Event.START_ARRAY, p.next());
                JsonArray a = p.getArray();
                assertEquals(3, a.size());
                assertEquals(1, a.getInt(0));
                assertEquals(3, a.getInt(2));
            }
        }

        @Test
        void getValue_for_scalar() {
            try (JsonParser p = Json.createParser(new StringReader("42"))) {
                p.next();
                JsonValue v = p.getValue();
                assertEquals(JsonValue.ValueType.NUMBER, v.getValueType());
            }
        }

        @Test
        void getValue_for_object() {
            try (JsonParser p = Json.createParser(new StringReader("{\"k\":true}"))) {
                p.next();
                JsonValue v = p.getValue();
                assertEquals(JsonValue.ValueType.OBJECT, v.getValueType());
            }
        }

        @Test
        void nested_structures() {
            try (JsonParser p = Json.createParser(new StringReader("{\"a\":[1,{\"b\":2}]}"))) {
                p.next(); // START_OBJECT
                JsonObject o = p.getObject();
                JsonArray arr = o.getJsonArray("a");
                assertEquals(2, arr.size());
                assertEquals(2, ((JsonObject) arr.get(1)).getInt("b"));
            }
        }
    }

    @Nested
    @DisplayName("createObjectBuilder(Map<String,?>) — M2.3")
    class FromMap {

        @Test
        void simple_map() {
            var src = new LinkedHashMap<String, Object>();
            src.put("name", "Alice");
            src.put("age", 30);
            src.put("active", true);
            JsonObject o = Json.createObjectBuilder(src).build();
            assertEquals("Alice", o.getString("name"));
            assertEquals(30, o.getInt("age"));
            assertEquals(JsonValue.TRUE, o.get("active"));
        }

        @Test
        void nested_map_and_list() {
            var inner = new LinkedHashMap<String, Object>();
            inner.put("x", 1);
            var src = new LinkedHashMap<String, Object>();
            src.put("data", inner);
            src.put("tags", List.of("a", "b"));
            JsonObject o = Json.createObjectBuilder(src).build();
            assertEquals(1, o.getJsonObject("data").getInt("x"));
            assertEquals(2, o.getJsonArray("tags").size());
            assertEquals("b", o.getJsonArray("tags").getString(1));
        }

        @Test
        void null_value_becomes_jsonvalue_null() {
            var src = new LinkedHashMap<String, Object>();
            src.put("k", null);
            JsonObject o = Json.createObjectBuilder(src).build();
            assertEquals(JsonValue.NULL, o.get("k"));
        }
    }

    @Nested
    @DisplayName("Json.createDiff — RFC 6902 §A.16")
    class Diff {

        private JsonObject obj(String s) {
            try (var r = Json.createReader(new StringReader(s))) {
                return r.readObject();
            }
        }

        @Test
        void identity_diff_is_empty() {
            JsonObject a = obj("{\"x\":1}");
            JsonPatch d = Json.createDiff(a, a);
            assertEquals(0, d.toJsonArray().size());
        }

        @Test
        void replace_scalar() {
            JsonPatch d = Json.createDiff(obj("{\"x\":1}"), obj("{\"x\":2}"));
            // 1 op : replace /x avec 2
            assertEquals(1, d.toJsonArray().size());
            assertEquals("replace", d.toJsonArray().getJsonObject(0).getString("op"));
            assertEquals("/x", d.toJsonArray().getJsonObject(0).getString("path"));
        }

        @Test
        void diff_then_apply_recovers_target() {
            JsonObject src = obj("{\"a\":1,\"b\":2,\"c\":{\"x\":1}}");
            JsonObject tgt = obj("{\"a\":1,\"b\":99,\"c\":{\"x\":1,\"y\":2},\"d\":\"new\"}");
            JsonPatch d = Json.createDiff(src, tgt);
            JsonObject back = d.apply(src);
            assertEquals(tgt, back);
        }

        @Test
        void diff_array_with_growth() {
            JsonPatch d = Json.createDiff(obj("{\"a\":[1,2]}"), obj("{\"a\":[1,2,3,4]}"));
            JsonObject back = d.apply(obj("{\"a\":[1,2]}"));
            assertEquals(obj("{\"a\":[1,2,3,4]}"), back);
        }

        @Test
        void diff_array_with_shrink() {
            JsonPatch d = Json.createDiff(obj("{\"a\":[1,2,3,4]}"), obj("{\"a\":[1,2]}"));
            JsonObject back = d.apply(obj("{\"a\":[1,2,3,4]}"));
            assertEquals(obj("{\"a\":[1,2]}"), back);
        }

        @Test
        void diff_with_pointer_escape() {
            // Key containing '/' : must be encoded as ~1 in the pointer
            JsonPatch d = Json.createDiff(obj("{\"a/b\":1}"), obj("{\"a/b\":2}"));
            assertEquals("/a~1b", d.toJsonArray().getJsonObject(0).getString("path"));
        }
    }
}
