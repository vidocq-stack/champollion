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
            // RFC 8259 : NaN/Infinity ne sont PAS des nombres JSON valides.
            assertThrows(NumberFormatException.class, () -> { try (var g = gen()) { g.write(Double.NaN); } });
            assertThrows(NumberFormatException.class, () -> { try (var g = gen()) { g.write(Double.POSITIVE_INFINITY); } });
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
            assertThrows(JsonGenerationException.class,
                    () -> { try (var g = gen()) { g.writeStartArray().write("k", 1); } });
        }

        @Test
        void rejects_unnamed_value_inside_object() {
            assertThrows(JsonGenerationException.class,
                    () -> { try (var g = gen()) { g.writeStartObject().write(1); } });
        }

        @Test
        void rejects_writeEnd_at_root() {
            assertThrows(JsonGenerationException.class,
                    () -> { try (var g = gen()) { g.writeEnd(); } });
        }

        @Test
        void rejects_two_root_values() {
            assertThrows(JsonGenerationException.class,
                    () -> { try (var g = gen()) { g.write(1); g.write(2); } });
        }

        @Test
        void rejects_close_with_open_container() {
            // close() ne doit pas masquer les containers ouverts
            assertThrows(JsonGenerationException.class, () -> {
                var g = gen();
                g.writeStartObject();
                g.close();
            });
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
