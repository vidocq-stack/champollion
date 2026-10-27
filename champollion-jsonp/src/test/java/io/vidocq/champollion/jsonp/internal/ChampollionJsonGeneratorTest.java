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

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChampollionJsonGeneratorTest {

    private final StringWriter out = new StringWriter();

    private JsonGenerator gen() {
        return new ChampollionJsonGenerator(out, /*pretty*/ false);
    }

    private JsonGenerator pretty() {
        return new ChampollionJsonGenerator(out, /*pretty*/ true);
    }

    @Nested
    @DisplayName("Buffering — the target Writer receives few, large writes")
    class Buffering {

        /** Counts the calls that reach the target, whatever their form. */
        static final class CountingWriter extends java.io.Writer {
            final StringBuilder content = new StringBuilder();
            int calls;

            @Override public void write(char[] cbuf, int off, int len) { calls++; content.append(cbuf, off, len); }
            @Override public void write(int c) { calls++; content.append((char) c); }
            @Override public void write(String str, int off, int len) { calls++; content.append(str, off, off + len); }
            @Override public void flush() {
                // Nothing to flush: the content is kept in memory for the assertions.
            }
            @Override public void close() {
                // Nothing to release: the content stays readable after the generator closes it.
            }
        }

        /**
         * Every value used to reach the target through a {@code BufferedWriter} of 256 characters,
         * whose every call takes a lock: about a dozen locked calls per small object, and a
         * write to the target every 256 characters. The generator now buffers its own output and
         * hands the target at most one write per kilobyte.
         */
        @Test
        void a_document_reaches_the_target_in_large_chunks() {
            var target = new CountingWriter();
            try (var g = new ChampollionJsonGenerator(target, false)) {
                g.writeStartArray();
                for (int i = 0; i < 100; i++) {
                    g.writeStartObject();
                    g.write("id", i);
                    g.write("title", "Todo " + i);
                    g.write("done", false);
                    g.writeEnd();
                }
                g.writeEnd();
            }
            String json = target.content.toString();
            assertEquals("[{\"id\":0,\"title\":\"Todo 0\",\"done\":false},{\"id\":1", json.substring(0, 47));
            assertEquals(']', json.charAt(json.length() - 1));
            org.junit.jupiter.api.Assertions.assertTrue(target.calls <= json.length() / 1024 + 1,
                    "the target writer was called " + target.calls + " times for " + json.length() + " characters");
        }

        @Test
        void a_string_with_escapes_is_written_whole() {
            var target = new CountingWriter();
            try (var g = new ChampollionJsonGenerator(target, false)) {
                g.write("a\"b\\c\n\u0001d");
            }
            assertEquals("\"a\\\"b\\\\c\\n\\u0001d\"", target.content.toString());
            assertEquals(1, target.calls);
        }

        @Test
        void a_value_longer_than_the_buffer_is_written_whole() {
            var target = new CountingWriter();
            String big = "x".repeat(5000);
            try (var g = new ChampollionJsonGenerator(target, false)) {
                g.write(big);
            }
            assertEquals("\"" + big + "\"", target.content.toString());
        }

        @Test
        void a_failing_target_surfaces_as_a_json_exception() {
            // Spec §3.4: I/O errors of the target are reported as JsonException.
            var failing = new java.io.Writer() {
                @Override public void write(char[] cbuf, int off, int len) throws java.io.IOException {
                    throw new java.io.IOException("disk full");
                }
                @Override public void flush() {
                    // Never reached: the write fails first.
                }
                @Override public void close() {
                    // Nothing to release.
                }
            };
            var g = new ChampollionJsonGenerator(failing, false);
            g.write(1);
            var e = assertThrows(jakarta.json.JsonException.class, g::flush);
            assertEquals("disk full", e.getCause().getMessage());
        }
    }

    @Nested
    @DisplayName("JsonValue arguments")
    class JsonValues {

        @Test
        void literal_values_are_written_as_json_literals() {
            try (var g = gen()) {
                g.writeStartArray()
                        .write(jakarta.json.JsonValue.TRUE)
                        .write(jakarta.json.JsonValue.FALSE)
                        .write(jakarta.json.JsonValue.NULL)
                        .writeEnd();
            }
            assertEquals("[true,false,null]", out.toString());
        }
    }

    @Nested
    @DisplayName("Spec §6.1 — primitives at root")
    class RootPrimitives {

        @Test
        void root_string() {
            try (var g = gen()) { g.write("hello"); }
            assertEquals("\"hello\"", out.toString());
        }

        @Test
        void root_int() {
            try (var g = gen()) { g.write(42); }
            assertEquals("42", out.toString());
        }

        @Test
        void root_long() {
            try (var g = gen()) { g.write(1234567890123L); }
            assertEquals("1234567890123", out.toString());
        }

        @Test
        void root_bigdecimal() {
            try (var g = gen()) { g.write(new BigDecimal("3.14")); }
            assertEquals("3.14", out.toString());
        }

        @Test
        void root_biginteger() {
            try (var g = gen()) { g.write(new BigInteger("12345678901234567890")); }
            assertEquals("12345678901234567890", out.toString());
        }

        @Test
        void root_boolean_and_null() {
            try (var g = gen()) { g.write(true); }
            assertEquals("true", out.toString());
        }

        @Test
        void root_double_rejects_nan_and_infinity() {
            // RFC 8259: NaN and Infinity are NOT valid JSON numbers.
            var nan = gen();
            assertThrows(NumberFormatException.class, () -> nan.write(Double.NaN));
            var infinity = gen();
            assertThrows(NumberFormatException.class, () -> infinity.write(Double.POSITIVE_INFINITY));
        }
    }

    @Nested
    @DisplayName("Objects")
    class Objects {

        @Test
        void empty_object() {
            try (var g = gen()) { g.writeStartObject().writeEnd(); }
            assertEquals("{}", out.toString());
        }

        @Test
        void single_member_object() {
            try (var g = gen()) {
                g.writeStartObject().write("a", 1).writeEnd();
            }
            assertEquals("{\"a\":1}", out.toString());
        }

        @Test
        void multi_member_preserve_order() {
            try (var g = gen()) {
                g.writeStartObject()
                        .write("a", "x")
                        .write("b", true)
                        .writeNull("c")
                        .writeEnd();
            }
            assertEquals("{\"a\":\"x\",\"b\":true,\"c\":null}", out.toString());
        }

        @Test
        void nested_object() {
            try (var g = gen()) {
                g.writeStartObject()
                        .writeStartObject("a")
                        .write("b", 1)
                        .writeEnd()
                        .writeEnd();
            }
            assertEquals("{\"a\":{\"b\":1}}", out.toString());
        }
    }

    @Nested
    @DisplayName("Arrays")
    class Arrays {

        @Test
        void empty_array() {
            try (var g = gen()) { g.writeStartArray().writeEnd(); }
            assertEquals("[]", out.toString());
        }

        @Test
        void mixed_values() {
            try (var g = gen()) {
                g.writeStartArray()
                        .write(1).write("x").write(true).write(false).writeNull()
                        .writeEnd();
            }
            assertEquals("[1,\"x\",true,false,null]", out.toString());
        }

        @Test
        void nested_array() {
            try (var g = gen()) {
                g.writeStartArray()
                        .writeStartArray().write(1).writeEnd()
                        .writeStartArray().write(2).writeEnd()
                        .writeEnd();
            }
            assertEquals("[[1],[2]]", out.toString());
        }
    }

    @Nested
    @DisplayName("RFC 8259 §7 — string escaping")
    class StringEscaping {

        @Test
        void escapes_quote_and_backslash() {
            try (var g = gen()) { g.write("\"\\"); }
            assertEquals("\"\\\"\\\\\"", out.toString());
        }

        @Test
        void escapes_short_form_control_chars() {
            try (var g = gen()) { g.write("\b\f\n\r\t"); }
            assertEquals("\"\\b\\f\\n\\r\\t\"", out.toString());
        }

        @Test
        void escapes_other_control_chars_as_unicode() {
            try (var g = gen()) { g.write(""); }
            assertEquals("\"\\u0001\\u0019\"", out.toString());
        }

        @Test
        void leaves_non_control_unicode_unchanged() {
            try (var g = gen()) { g.write("éàç"); }
            assertEquals("\"éàç\"", out.toString());
        }
    }

    @Nested
    @DisplayName("Pretty printing")
    class Pretty {

        @Test
        void empty_object_pretty() {
            try (var g = pretty()) { g.writeStartObject().writeEnd(); }
            assertEquals("{}", out.toString());
        }

        @Test
        void object_pretty_uses_indentation_and_lf() {
            try (var g = pretty()) {
                g.writeStartObject().write("a", 1).write("b", 2).writeEnd();
            }
            assertEquals("{\n    \"a\": 1,\n    \"b\": 2\n}", out.toString());
        }

        @Test
        void nested_pretty() {
            try (var g = pretty()) {
                g.writeStartObject()
                        .writeStartArray("a")
                        .write(1).write(2)
                        .writeEnd()
                        .writeEnd();
            }
            assertEquals("{\n    \"a\": [\n        1,\n        2\n    ]\n}", out.toString());
        }
    }

    @Nested
    @DisplayName("State machine errors")
    class StateMachine {

        @Test
        void rejects_member_outside_object() {
            var g = gen();
            g.writeStartArray();
            assertThrows(JsonGenerationException.class, () -> g.write("k", 1));
        }

        @Test
        void rejects_unnamed_value_inside_object() {
            var g = gen();
            g.writeStartObject();
            assertThrows(JsonGenerationException.class, () -> g.write(1));
        }

        @Test
        void rejects_writeEnd_at_root() {
            var g = gen();
            assertThrows(JsonGenerationException.class, g::writeEnd);
        }

        @Test
        void rejects_two_root_values() {
            var g = gen();
            g.write(1);
            assertThrows(JsonGenerationException.class, () -> g.write(2));
        }

        @Test
        void rejects_close_with_open_container() {
            // close() must not hide the open containers
            var g = gen();
            g.writeStartObject();
            assertThrows(JsonGenerationException.class, g::close);
        }
    }

    @Test
    void flush_is_safe_at_any_state() {
        try (var g = gen()) {
            g.writeStartArray();
            g.flush();
            g.write(1);
            g.flush();
            g.writeEnd();
            g.flush();
        }
        assertEquals("[1]", out.toString());
    }
}
