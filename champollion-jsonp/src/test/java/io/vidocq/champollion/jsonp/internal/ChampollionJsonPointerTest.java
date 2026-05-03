package io.vidocq.champollion.jsonp.internal;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChampollionJsonPointer — RFC 6901")
class ChampollionJsonPointerTest {

    /** Le document RFC 6901 §5 utilisé comme référence canonique. */
    private static final String RFC6901_DOC = """
            {
                "foo": ["bar", "baz"],
                "": 0,
                "a/b": 1,
                "c%d": 2,
                "e^f": 3,
                "g|h": 4,
                "i\\\\j": 5,
                "k\\"l": 6,
                " ": 7,
                "m~n": 8
            }
            """;

    private static JsonStructure doc() {
        try (var r = Json.createReader(new StringReader(RFC6901_DOC))) {
            return r.read();
        }
    }

    @Nested
    @DisplayName("Parsing & syntax")
    class Parsing {

        @Test
        void empty_string_means_whole_document() {
            var p = new ChampollionJsonPointer("");
            assertEquals(doc(), p.getValue(doc()));
        }

        @Test
        void rejects_pointer_not_starting_with_slash() {
            assertThrows(JsonException.class, () -> new ChampollionJsonPointer("foo"));
        }

        @Test
        void escape_tilde_and_slash_in_token_names() {
            // a/b → "/a~1b" ; m~n → "/m~0n"
            var doc = doc();
            assertEquals(1, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/a~1b").getValue(doc)).intValue());
            assertEquals(8, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/m~0n").getValue(doc)).intValue());
        }

        @Test
        void rejects_invalid_escape() {
            // ~ doit être suivi de 0 ou 1 (RFC 6901 §3) — erreur différée jusqu'à
            // la première utilisation (compat TCK testResolvePathWithUnencodedTilde
            // qui exige la construction tolérante).
            var p = new ChampollionJsonPointer("/~2");
            assertThrows(JsonException.class, () -> p.getValue(Json.createObjectBuilder().build()));
        }
    }

    @Nested
    @DisplayName("RFC 6901 §5 — reference document evaluation")
    class ReferenceEval {

        @Test
        void all_examples_from_section_5() {
            var doc = doc();
            assertEquals("bar", ((jakarta.json.JsonString) new ChampollionJsonPointer("/foo/0").getValue(doc)).getString());
            assertEquals(0, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/").getValue(doc)).intValue());
            assertEquals(1, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/a~1b").getValue(doc)).intValue());
            assertEquals(2, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/c%d").getValue(doc)).intValue());
            assertEquals(3, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/e^f").getValue(doc)).intValue());
            assertEquals(4, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/g|h").getValue(doc)).intValue());
            assertEquals(5, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/i\\j").getValue(doc)).intValue());
            assertEquals(6, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/k\"l").getValue(doc)).intValue());
            assertEquals(7, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/ ").getValue(doc)).intValue());
            assertEquals(8, ((jakarta.json.JsonNumber) new ChampollionJsonPointer("/m~0n").getValue(doc)).intValue());
        }

        @Test
        void array_index_out_of_bounds_throws() {
            assertThrows(JsonException.class,
                    () -> new ChampollionJsonPointer("/foo/2").getValue(doc()));
        }

        @Test
        void unknown_member_name_throws() {
            assertThrows(JsonException.class,
                    () -> new ChampollionJsonPointer("/missing").getValue(doc()));
        }

        @Test
        void array_index_with_leading_zero_is_invalid() {
            // RFC 6901 §4 : les indices ne peuvent pas avoir de zéros initiaux (sauf "0")
            assertThrows(JsonException.class,
                    () -> new ChampollionJsonPointer("/foo/01").getValue(doc()));
        }
    }

    @Nested
    @DisplayName("Predicate API")
    class Contains {

        @Test
        void containsValue_true_for_existing() {
            assertTrue(new ChampollionJsonPointer("/foo/0").containsValue(doc()));
        }

        @Test
        void containsValue_false_for_missing_member() {
            assertFalse(new ChampollionJsonPointer("/missing").containsValue(doc()));
        }

        @Test
        void containsValue_false_for_oob_index() {
            assertFalse(new ChampollionJsonPointer("/foo/99").containsValue(doc()));
        }
    }

    @Nested
    @DisplayName("Mutation API — returns NEW structure (immutability)")
    class Mutations {

        @Test
        void add_object_member_returns_new_structure() {
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/new").add(doc, Json.createValue("hello"));
            assertEquals("hello", ((jakarta.json.JsonString) ((JsonObject) updated).get("new")).getString());
            // L'original n'est pas modifié
            assertFalse(((JsonObject) doc).containsKey("new"));
        }

        @Test
        void add_array_index_inserts() {
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/foo/1").add(doc, Json.createValue("INSERTED"));
            var newFoo = ((JsonObject) updated).getJsonArray("foo");
            assertEquals("bar", newFoo.getString(0));
            assertEquals("INSERTED", newFoo.getString(1));
            assertEquals("baz", newFoo.getString(2));
        }

        @Test
        void add_with_dash_appends_to_array() {
            // RFC 6901 §4 : '-' = "insertion past last element"
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/foo/-").add(doc, Json.createValue("appended"));
            var newFoo = ((JsonObject) updated).getJsonArray("foo");
            assertEquals(3, newFoo.size());
            assertEquals("appended", newFoo.getString(2));
        }

        @Test
        void replace_throws_if_target_missing() {
            assertThrows(JsonException.class,
                    () -> new ChampollionJsonPointer("/missing").replace(doc(), JsonValue.NULL));
        }

        @Test
        void replace_works_on_existing_member() {
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/foo/0").replace(doc, Json.createValue("REPLACED"));
            assertEquals("REPLACED", ((JsonObject) updated).getJsonArray("foo").getString(0));
        }

        @Test
        void remove_object_member() {
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/m~0n").remove(doc);
            assertFalse(((JsonObject) updated).containsKey("m~n"));
        }

        @Test
        void remove_array_index_shifts() {
            var doc = doc();
            JsonStructure updated = new ChampollionJsonPointer("/foo/0").remove(doc);
            var newFoo = ((JsonObject) updated).getJsonArray("foo");
            assertEquals(1, newFoo.size());
            assertEquals("baz", newFoo.getString(0));
        }
    }
}
