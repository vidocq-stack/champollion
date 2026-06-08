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

import jakarta.json.JsonException;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParsingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChampollionJsonParserTest {

    private static JsonParser parser(String s) {
        return new ChampollionJsonParser(new StringReader(s));
    }

    private static List<Event> events(String s) {
        var out = new ArrayList<Event>();
        try (var p = parser(s)) {
            while (p.hasNext()) out.add(p.next());
        }
        return out;
    }

    @Nested
    @DisplayName("Spec §3 — primitive root values")
    class Primitives {

        @Test
        void empty_object() {
            assertEquals(List.of(Event.START_OBJECT, Event.END_OBJECT), events("{}"));
        }

        @Test
        void empty_array() {
            assertEquals(List.of(Event.START_ARRAY, Event.END_ARRAY), events("[]"));
        }

        @Test
        void root_string() {
            try (var p = parser("\"hello\"")) {
                assertTrue(p.hasNext());
                assertEquals(Event.VALUE_STRING, p.next());
                assertEquals("hello", p.getString());
                assertFalse(p.hasNext());
            }
        }

        @Test
        void root_number_integer() {
            try (var p = parser("42")) {
                assertEquals(Event.VALUE_NUMBER, p.next());
                assertTrue(p.isIntegralNumber());
                assertEquals(42, p.getInt());
                assertEquals(42L, p.getLong());
                assertEquals(BigInteger.valueOf(42), p.getBigDecimal().toBigInteger());
                assertEquals(new BigDecimal("42"), p.getBigDecimal());
            }
        }

        @Test
        void root_number_decimal() {
            try (var p = parser("3.14")) {
                assertEquals(Event.VALUE_NUMBER, p.next());
                assertFalse(p.isIntegralNumber());
                assertEquals(new BigDecimal("3.14"), p.getBigDecimal());
            }
        }

        @Test
        void root_true_false_null() {
            assertEquals(List.of(Event.VALUE_TRUE), events("true"));
            assertEquals(List.of(Event.VALUE_FALSE), events("false"));
            assertEquals(List.of(Event.VALUE_NULL), events("null"));
        }
    }

    @Nested
    @DisplayName("Spec §4 — objects with members")
    class Objects {

        @Test
        void single_member() {
            try (var p = parser("{\"a\":1}")) {
                assertEquals(Event.START_OBJECT, p.next());
                assertEquals(Event.KEY_NAME, p.next());
                assertEquals("a", p.getString());
                assertEquals(Event.VALUE_NUMBER, p.next());
                assertEquals(1, p.getInt());
                assertEquals(Event.END_OBJECT, p.next());
                assertFalse(p.hasNext());
            }
        }

        @Test
        void multiple_members_preserve_order() {
            assertEquals(List.of(
                    Event.START_OBJECT,
                    Event.KEY_NAME, Event.VALUE_STRING,
                    Event.KEY_NAME, Event.VALUE_TRUE,
                    Event.KEY_NAME, Event.VALUE_NULL,
                    Event.END_OBJECT
            ), events("{\"a\":\"x\",\"b\":true,\"c\":null}"));
        }

        @Test
        void nested_objects() {
            assertEquals(List.of(
                    Event.START_OBJECT,
                    Event.KEY_NAME, Event.START_OBJECT,
                    Event.KEY_NAME, Event.VALUE_NUMBER,
                    Event.END_OBJECT,
                    Event.END_OBJECT
            ), events("{\"a\":{\"b\":1}}"));
        }
    }

    @Nested
    @DisplayName("Spec §5 — arrays")
    class Arrays {

        @Test
        void mixed_values() {
            assertEquals(List.of(
                    Event.START_ARRAY,
                    Event.VALUE_NUMBER, Event.VALUE_STRING,
                    Event.VALUE_TRUE, Event.VALUE_FALSE, Event.VALUE_NULL,
                    Event.END_ARRAY
            ), events("[1,\"x\",true,false,null]"));
        }

        @Test
        void nested_arrays() {
            assertEquals(List.of(
                    Event.START_ARRAY,
                    Event.START_ARRAY, Event.VALUE_NUMBER, Event.END_ARRAY,
                    Event.START_ARRAY, Event.VALUE_NUMBER, Event.END_ARRAY,
                    Event.END_ARRAY
            ), events("[[1],[2]]"));
        }
    }

    @Nested
    @DisplayName("Structural errors")
    class Errors {

        @Test
        void rejects_object_missing_colon() {
            assertThrows(JsonParsingException.class, () -> events("{\"a\" 1}"));
        }

        @Test
        void rejects_object_missing_comma() {
            assertThrows(JsonParsingException.class, () -> events("{\"a\":1 \"b\":2}"));
        }

        @Test
        void rejects_trailing_comma_object() {
            assertThrows(JsonParsingException.class, () -> events("{\"a\":1,}"));
        }

        @Test
        void rejects_trailing_comma_array() {
            assertThrows(JsonParsingException.class, () -> events("[1,2,]"));
        }

        @Test
        void rejects_unbalanced_braces() {
            assertThrows(JsonParsingException.class, () -> events("{"));
            assertThrows(JsonParsingException.class, () -> events("[1,2"));
            assertThrows(JsonParsingException.class, () -> events("}"));
        }

        @Test
        void rejects_multiple_roots() {
            assertThrows(JsonParsingException.class, () -> events("1 2"));
            assertThrows(JsonParsingException.class, () -> events("{} {}"));
        }

        @Test
        void rejects_empty_input() {
            assertThrows(JsonParsingException.class, () -> events(""));
        }

        @Test
        void getString_outside_string_event_throws() {
            try (var p = parser("[1]")) {
                p.next(); // START_ARRAY
                assertThrows(IllegalStateException.class, p::getString);
            }
        }
    }

    @Test
    void hasNext_idempotent() {
        try (var p = parser("[1]")) {
            assertTrue(p.hasNext());
            assertTrue(p.hasNext());
            assertEquals(Event.START_ARRAY, p.next());
        }
    }

    @Test
    void io_error_wrapped_as_json_exception() {
        var bad = new java.io.Reader() {
            @Override public int read(char[] cbuf, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
            @Override public void close() {}
        };
        var p = new ChampollionJsonParser(bad);
        assertThrows(JsonException.class, p::hasNext);
    }
}
