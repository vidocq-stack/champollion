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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTokenizerTest {

    private static List<JsonToken> tokenize(String input) {
        var t = new JsonReaderTokenizer(new StringReader(input));
        var out = new ArrayList<JsonToken>();
        while (true) {
            var tok = t.next();
            out.add(tok);
            if (tok == JsonToken.EOF) return out;
        }
    }

    /** Scans the single scalar of {@code input} and materializes it (Reader mode). */
    private static String scanValue(String input) {
        var t = new JsonReaderTokenizer(new StringReader(input));
        var tok = t.next();
        assertTrue(tok == JsonToken.STRING || tok == JsonToken.NUMBER, "scalar expected, got " + tok);
        return t.currentString();
    }

    @Nested
    @DisplayName("RFC 8259 §2 — structural characters")
    class Structural {

        @Test
        void recognizes_braces_brackets_separators() {
            var tokens = tokenize("{}[]:,");
            assertEquals(List.of(
                    JsonToken.START_OBJECT, JsonToken.END_OBJECT,
                    JsonToken.START_ARRAY, JsonToken.END_ARRAY,
                    JsonToken.NAME_SEPARATOR, JsonToken.VALUE_SEPARATOR,
                    JsonToken.EOF), tokens);
        }

        @Test
        void skips_all_whitespace_kinds_rfc8259_section2() {
            // RFC 8259 §2 : ws = *(%x20 / %x09 / %x0A / %x0D)
            var tokens = tokenize(" \t\n\r {  }\n");
            assertEquals(List.of(JsonToken.START_OBJECT, JsonToken.END_OBJECT, JsonToken.EOF), tokens);
        }

        @Test
        void empty_input_yields_eof_only() {
            assertEquals(List.of(JsonToken.EOF), tokenize(""));
            assertEquals(List.of(JsonToken.EOF), tokenize("   "));
        }
    }

    @Nested
    @DisplayName("RFC 8259 §3 — keywords")
    class Keywords {

        @Test
        void recognizes_true_false_null() {
            var tokens = tokenize("true false null");
            assertEquals(JsonToken.TRUE, tokens.get(0));
            assertEquals(JsonToken.FALSE, tokens.get(1));
            assertEquals(JsonToken.NULL, tokens.get(2));
        }

        @Test
        void rejects_partial_keyword() {
            assertThrows(JsonException.class, () -> tokenize("tru"));
            assertThrows(JsonException.class, () -> tokenize("fals"));
            assertThrows(JsonException.class, () -> tokenize("nul"));
            assertThrows(JsonException.class, () -> tokenize("True"));
        }
    }

    @Nested
    @DisplayName("RFC 8259 §6 — numbers")
    class Numbers {

        @Test
        void integers_and_negatives() {
            assertEquals("0", scanValue("0"));
            assertEquals("-0", scanValue("-0"));
            assertEquals("42", scanValue("42"));
            assertEquals("-42", scanValue("-42"));
            assertEquals("123456789", scanValue("123456789"));
        }

        @Test
        void fractions_and_exponents() {
            assertEquals("3.14", scanValue("3.14"));
            assertEquals("1e10", scanValue("1e10"));
            assertEquals("1E+10", scanValue("1E+10"));
            assertEquals("-1.5e-3", scanValue("-1.5e-3"));
            assertEquals("0.5", scanValue("0.5"));
        }

        @Test
        void rejects_leading_zero_int() {
            // RFC 8259 §6 : int = zero / digit1-9 *DIGIT  → "01" interdit
            assertThrows(JsonException.class, () -> tokenize("01"));
        }

        @Test
        void rejects_lonely_minus_or_dot() {
            assertThrows(JsonException.class, () -> tokenize("-"));
            assertThrows(JsonException.class, () -> tokenize("1."));
            assertThrows(JsonException.class, () -> tokenize(".5"));
        }

        @Test
        void rejects_exponent_without_digits() {
            assertThrows(JsonException.class, () -> tokenize("1e"));
            assertThrows(JsonException.class, () -> tokenize("1e+"));
        }
    }

    @Nested
    @DisplayName("RFC 8259 §7 — strings")
    class Strings {

        @Test
        void empty_and_simple_string() {
            assertEquals("", scanValue("\"\""));
            assertEquals("hello", scanValue("\"hello\""));
        }

        @Test
        void escape_sequences() {
            assertEquals("\"", scanValue("\"\\\"\""));
            assertEquals("\\", scanValue("\"\\\\\""));
            assertEquals("/", scanValue("\"\\/\""));
            assertEquals("\b\f\n\r\t", scanValue("\"\\b\\f\\n\\r\\t\""));
        }

        @Test
        void unicode_escape() {
            assertEquals("A", scanValue("\"\\u0041\""));
            assertEquals("é", scanValue("\"\\u00E9\""));
            // mixed casing
            assertEquals("«", scanValue("\"\\u00aB\""));
        }

        @Test
        void rejects_unterminated_string() {
            assertThrows(JsonException.class, () -> tokenize("\"hello"));
        }

        @Test
        void rejects_unescaped_control_character() {
            // RFC 8259 §7 : control characters U+0000..U+001F MUST be escaped
            assertThrows(JsonException.class, () -> tokenize("\"a\nb\""));
            assertThrows(JsonException.class, () -> tokenize("\"ab\""));
        }

        @Test
        void rejects_invalid_escape() {
            assertThrows(JsonException.class, () -> tokenize("\"\\x\""));
            assertThrows(JsonException.class, () -> tokenize("\"\\u00G0\""));
            assertThrows(JsonException.class, () -> tokenize("\"\\u00\""));
        }
    }

    @Nested
    @DisplayName("Composite inputs")
    class Composite {

        @Test
        void object_with_one_member() {
            var tokens = tokenize("{\"a\":1}");
            assertEquals(List.of(
                    JsonToken.START_OBJECT, JsonToken.STRING, JsonToken.NAME_SEPARATOR,
                    JsonToken.NUMBER, JsonToken.END_OBJECT, JsonToken.EOF), tokens);
        }

        @Test
        void array_with_mixed_values() {
            var tokens = tokenize("[1,\"x\",true,null]");
            assertEquals(List.of(
                    JsonToken.START_ARRAY, JsonToken.NUMBER, JsonToken.VALUE_SEPARATOR,
                    JsonToken.STRING, JsonToken.VALUE_SEPARATOR, JsonToken.TRUE,
                    JsonToken.VALUE_SEPARATOR, JsonToken.NULL, JsonToken.END_ARRAY,
                    JsonToken.EOF), tokens);
        }
    }

    @Nested
    @DisplayName("P12 — lazy pending value")
    class LazyPendingValue {

        @Test
        void pending_string_survives_separator_scans_across_refill() {
            // String fully inside the first 512-char buffer, then enough
            // whitespace that scanning the separator forces a refill: the
            // un-materialized range must be promoted into the scratch.
            String value = "v".repeat(400);
            String input = "\"" + value + "\"" + " ".repeat(JsonReaderTokenizer.BUF_SIZE) + ",1";
            var t = new JsonReaderTokenizer(new StringReader(input));
            assertEquals(JsonToken.STRING, t.next());
            assertEquals(JsonToken.VALUE_SEPARATOR, t.next()); // refill happens here
            assertEquals(value, t.currentString());
        }

        @Test
        void pending_number_survives_separator_scans_across_refill() {
            String input = "12345" + " ".repeat(JsonReaderTokenizer.BUF_SIZE) + ",";
            var t = new JsonReaderTokenizer(new StringReader(input));
            assertEquals(JsonToken.NUMBER, t.next());
            assertEquals(JsonToken.VALUE_SEPARATOR, t.next());
            assertEquals(new BigDecimal("12345"), t.currentBigDecimal());
            assertTrue(t.currentIntegral());
            assertEquals(12345L, t.currentLong());
        }

        @Test
        void scalar_crossing_buffer_boundary_is_complete() {
            // Longer than BUF_SIZE: the scan itself crosses a refill and runs
            // in scratch mode end to end.
            String value = "x".repeat(JsonReaderTokenizer.BUF_SIZE + 100);
            assertEquals(value, scanValue("\"" + value + "\""));
            String number = "1" + "0".repeat(JsonReaderTokenizer.BUF_SIZE + 50);
            assertEquals(number, scanValue(number + " "));
        }

        @Test
        void materialized_string_is_cached() {
            var t = new JsonReaderTokenizer(new StringReader("\"abc\""));
            assertEquals(JsonToken.STRING, t.next());
            assertTrue(t.currentString() == t.currentString(), "same instance expected");
        }

        @Test
        void number_accessors_on_range_without_string() {
            var t = new JsonReaderTokenizer(new StringReader("[-9876543210]"));
            assertEquals(JsonToken.START_ARRAY, t.next());
            assertEquals(JsonToken.NUMBER, t.next());
            assertTrue(t.currentIntegral());
            assertEquals(-9876543210L, t.currentLong());
            assertEquals(new BigDecimal("-9876543210"), t.currentBigDecimal());
        }

        @Test
        void long_falls_back_to_bigdecimal_semantics() {
            // 19+ digits: beyond the direct-parse window, BigDecimal fallback.
            var t = new JsonReaderTokenizer(new StringReader("12345678901234567890123 "));
            assertEquals(JsonToken.NUMBER, t.next());
            assertEquals(new BigDecimal("12345678901234567890123").longValue(), t.currentLong());
            // Non-integral: truncation toward zero.
            var t2 = new JsonReaderTokenizer(new StringReader("3.99 "));
            assertEquals(JsonToken.NUMBER, t2.next());
            assertFalse(t2.currentIntegral());
            assertEquals(3L, t2.currentLong());
        }
    }

    @Nested
    @DisplayName("P12 — String-mode tokenizer parity")
    class StringMode {

        private String scanValueS(String input) {
            var t = new JsonStringTokenizer(input);
            var tok = t.next();
            assertTrue(tok == JsonToken.STRING || tok == JsonToken.NUMBER, "scalar expected, got " + tok);
            return t.currentString();
        }

        @Test
        void fast_and_slow_string_paths() {
            assertEquals("hello", scanValueS("\"hello\""));
            assertEquals("", scanValueS("\"\""));
            assertEquals("a\"b", scanValueS("\"a\\\"b\""));
            assertEquals("é", scanValueS("\"\\u00E9\""));
        }

        @Test
        void numbers_including_eof_terminated() {
            assertEquals("42", scanValueS("42"));
            assertEquals("-1.5e-3", scanValueS("-1.5e-3"));
            assertEquals("0", scanValueS("0"));
            var t = new JsonStringTokenizer("3.14");
            assertEquals(JsonToken.NUMBER, t.next());
            assertFalse(t.currentIntegral());
            assertEquals(new BigDecimal("3.14"), t.currentBigDecimal());
        }

        @Test
        void rejects_invalid_numbers_and_strings() {
            assertThrows(JsonException.class, () -> new JsonStringTokenizer("01").next());
            assertThrows(JsonException.class, () -> new JsonStringTokenizer("-").next());
            assertThrows(JsonException.class, () -> new JsonStringTokenizer("1e").next());
            assertThrows(JsonException.class, () -> new JsonStringTokenizer("\"abc").next());
            assertThrows(JsonException.class, () -> new JsonStringTokenizer("\"a\nb\"").next());
        }

        @Test
        void pending_value_survives_separator_scans() {
            var t = new JsonStringTokenizer("\"abc\"   ,");
            assertEquals(JsonToken.STRING, t.next());
            assertEquals(JsonToken.VALUE_SEPARATOR, t.next());
            assertEquals("abc", t.currentString());
        }
    }

    @Test
    void rejects_unexpected_character() {
        assertThrows(JsonException.class, () -> tokenize("@"));
        assertThrows(JsonException.class, () -> tokenize("'single quotes'"));
    }
}
