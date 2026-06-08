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

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ChampollionJsonPatch — RFC 6902")
class ChampollionJsonPatchTest {

    private static JsonStructure read(String s) {
        try (var r = Json.createReader(new StringReader(s))) {
            return r.read();
        }
    }

    private static JsonArray patch(String s) {
        return (JsonArray) read(s);
    }

    @Nested
    @DisplayName("RFC 6902 §A — appendix examples")
    class AppendixA {

        @Test
        void A1_adding_an_object_member() {
            // RFC 6902 §A.1
            var doc = read("{\"foo\":\"bar\"}");
            var p = patch("[{\"op\":\"add\",\"path\":\"/baz\",\"value\":\"qux\"}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals("bar", result.getString("foo"));
            assertEquals("qux", result.getString("baz"));
        }

        @Test
        void A2_adding_an_array_element() {
            // §A.2
            var doc = read("{\"foo\":[\"bar\",\"baz\"]}");
            var p = patch("[{\"op\":\"add\",\"path\":\"/foo/1\",\"value\":\"qux\"}]");
            JsonArray foo = ((JsonObject) new ChampollionJsonPatch(p).apply(doc)).getJsonArray("foo");
            assertEquals(3, foo.size());
            assertEquals("qux", foo.getString(1));
        }

        @Test
        void A3_removing_an_object_member() {
            var doc = read("{\"foo\":\"bar\",\"baz\":\"qux\"}");
            var p = patch("[{\"op\":\"remove\",\"path\":\"/baz\"}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals(1, result.size());
        }

        @Test
        void A4_removing_an_array_element() {
            var doc = read("{\"foo\":[\"bar\",\"qux\",\"baz\"]}");
            var p = patch("[{\"op\":\"remove\",\"path\":\"/foo/1\"}]");
            JsonArray foo = ((JsonObject) new ChampollionJsonPatch(p).apply(doc)).getJsonArray("foo");
            assertEquals(2, foo.size());
            assertEquals("baz", foo.getString(1));
        }

        @Test
        void A5_replacing_a_value() {
            var doc = read("{\"foo\":\"bar\",\"baz\":\"qux\"}");
            var p = patch("[{\"op\":\"replace\",\"path\":\"/baz\",\"value\":\"boo\"}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals("boo", result.getString("baz"));
        }

        @Test
        void A6_moving_a_value() {
            var doc = read("{\"foo\":{\"bar\":\"baz\",\"waldo\":\"fred\"},\"qux\":{\"corge\":\"grault\"}}");
            var p = patch("[{\"op\":\"move\",\"from\":\"/foo/waldo\",\"path\":\"/qux/thud\"}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals("fred", result.getJsonObject("qux").getString("thud"));
            assertEquals(false, result.getJsonObject("foo").containsKey("waldo"));
        }

        @Test
        void A7_moving_an_array_element() {
            var doc = read("{\"foo\":[\"all\",\"grass\",\"cows\",\"eat\"]}");
            var p = patch("[{\"op\":\"move\",\"from\":\"/foo/1\",\"path\":\"/foo/3\"}]");
            JsonArray foo = ((JsonObject) new ChampollionJsonPatch(p).apply(doc)).getJsonArray("foo");
            // RFC 6902 §A.7 : ["all", "cows", "eat", "grass"]
            assertEquals("all", foo.getString(0));
            assertEquals("cows", foo.getString(1));
            assertEquals("eat", foo.getString(2));
            assertEquals("grass", foo.getString(3));
        }

        @Test
        void A8_test_op_succeeds_when_value_matches() {
            var doc = read("{\"baz\":\"qux\",\"foo\":[\"a\",2,\"c\"]}");
            var p = patch("""
                    [
                        {"op":"test","path":"/baz","value":"qux"},
                        {"op":"test","path":"/foo/1","value":2}
                    ]
                    """);
            // no exception = success
            new ChampollionJsonPatch(p).apply(doc);
        }

        @Test
        void A9_test_op_fails_when_value_differs() {
            var doc = read("{\"baz\":\"qux\"}");
            var p = patch("[{\"op\":\"test\",\"path\":\"/baz\",\"value\":\"bar\"}]");
            assertThrows(JsonException.class, () -> new ChampollionJsonPatch(p).apply(doc));
        }

        @Test
        void A10_adding_a_nested_member_object() {
            var doc = read("{\"foo\":\"bar\"}");
            var p = patch("[{\"op\":\"add\",\"path\":\"/child\",\"value\":{\"grandchild\":{}}}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals(0, result.getJsonObject("child").getJsonObject("grandchild").size());
        }

        @Test
        void A14_dash_appends_to_array() {
            var doc = read("{\"foo\":[\"bar\"]}");
            var p = patch("[{\"op\":\"add\",\"path\":\"/foo/-\",\"value\":[\"abc\",\"def\"]}]");
            JsonArray foo = ((JsonObject) new ChampollionJsonPatch(p).apply(doc)).getJsonArray("foo");
            assertEquals(2, foo.size());
            assertEquals(2, foo.getJsonArray(1).size());
        }

        @Test
        void copy_op() {
            var doc = read("{\"a\":1,\"target\":{}}");
            var p = patch("[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/target/b\"}]");
            JsonObject result = (JsonObject) new ChampollionJsonPatch(p).apply(doc);
            assertEquals(1, result.getJsonObject("target").getInt("b"));
            assertEquals(1, result.getInt("a")); // l'original reste
        }
    }

    @Nested
    @DisplayName("Validation / errors")
    class Errors {

        @Test
        void rejects_unknown_op() {
            var doc = read("{}");
            var p = patch("[{\"op\":\"frobulate\",\"path\":\"/x\"}]");
            assertThrows(JsonException.class, () -> new ChampollionJsonPatch(p).apply(doc));
        }

        @Test
        void rejects_op_missing_required_member() {
            var doc = read("{}");
            var p = patch("[{\"op\":\"add\"}]"); // path manquant
            assertThrows(JsonException.class, () -> new ChampollionJsonPatch(p).apply(doc));
        }

        @Test
        void rejects_replace_on_missing_path() {
            var doc = read("{}");
            var p = patch("[{\"op\":\"replace\",\"path\":\"/x\",\"value\":1}]");
            assertThrows(JsonException.class, () -> new ChampollionJsonPatch(p).apply(doc));
        }
    }

    @Nested
    @DisplayName("JsonPatchBuilder")
    class Builder {

        @Test
        void builder_produces_equivalent_patch() {
            var b = new ChampollionJsonPatchBuilder();
            JsonPatch p = b.add("/a", JsonValue.TRUE)
                    .replace("/a", JsonValue.FALSE)
                    .build();
            var doc = read("{\"x\":0}");
            JsonObject after = (JsonObject) p.apply(doc);
            assertEquals(JsonValue.FALSE, after.get("a"));
        }
    }
}
