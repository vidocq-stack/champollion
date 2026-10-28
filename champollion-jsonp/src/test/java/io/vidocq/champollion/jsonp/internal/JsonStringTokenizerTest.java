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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsonStringTokenizer — String-mode grammar, escapes, positions and errors")
class JsonStringTokenizerTest {

    private static List<JsonToken> tokens(String json) {
        var t = new JsonStringTokenizer(json);
        List<JsonToken> out = new ArrayList<>();
        JsonToken tok;
        while ((tok = t.next()) != JsonToken.EOF) out.add(tok);
        return out;
    }

    private static String scalar(String json) {
        var t = new JsonStringTokenizer(json);
        t.next();
        return t.currentString();
    }

    private static String errorOf(String json) {
        var t = new JsonStringTokenizer(json);
        JsonException e = assertThrows(JsonException.class, () -> {
            while (t.next() != JsonToken.EOF) {
                // drain until the error
            }
        });
        return e.getMessage();
    }

    @Test
    void structural_tokens_and_keywords() {
        assertEquals(List.of(JsonToken.START_OBJECT, JsonToken.STRING, JsonToken.NAME_SEPARATOR,
                        JsonToken.START_ARRAY, JsonToken.TRUE, JsonToken.VALUE_SEPARATOR, JsonToken.FALSE,
                        JsonToken.VALUE_SEPARATOR, JsonToken.NULL, JsonToken.END_ARRAY, JsonToken.END_OBJECT),
                tokens("{ \"k\" :\t[true,false,null]\r}"));
    }

    @Test
    void newlines_move_the_line_and_reset_the_column() {
        var t = new JsonStringTokenizer("[\n  1,\n\n 2]");
        assertEquals(JsonToken.START_ARRAY, t.next());
        assertEquals(1L, t.line());
        assertEquals(JsonToken.NUMBER, t.next());
        assertEquals(2L, t.line());
        assertEquals(3L, t.column());
        t.next();
        assertEquals(JsonToken.NUMBER, t.next());
        assertEquals(4L, t.line());
        assertEquals(2L, t.column());
        assertEquals(10L, t.offset());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"a\\\"b\"|a\"b",
            "\"a\\\\b\"|a\\b",
            "\"a\\/b\"|a/b",
            "\"\\u0041\\u00e9\\u20AC\"|Aé€",
            "\"x\\ty\"|x\ty",
    })
    void escapes_are_decoded(String json, String expected) {
        assertEquals(expected, scalar(json));
    }

    @Test
    void control_escapes_are_decoded() {
        assertEquals("\b\f\n\r\t", scalar("\"\\b\\f\\n\\r\\t\""));
        assertEquals("pre\nfix", scalar("\"pre\\nfix\""));
    }

    @Test
    void string_errors_are_reported() {
        assertTrue(errorOf("\"abc").contains("Unterminated string"));
        assertTrue(errorOf("\"a\\").contains("Unterminated escape"));
        assertTrue(errorOf("\"a\\q\"").contains("Invalid escape"));
        assertTrue(errorOf("\"\\u12").contains("Unterminated unicode"));
        assertTrue(errorOf("\"\\u12G4\"").contains("Invalid hex digit"));
        assertTrue(errorOf("\"a\\n\u0001\"").contains("U+0001"));
    }

    @Test
    void keyword_mismatch_and_unexpected_character_are_reported() {
        assertTrue(errorOf("tru").contains("Expected 'true'"));
        assertTrue(errorOf("nul1").contains("Expected 'null'"));
        assertTrue(errorOf("fals").contains("Expected 'false'"));
        assertTrue(errorOf("[1;2]").contains("Unexpected character"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "-12", "7", "1.25", "-0.5", "1e5", "1E+5", "2.5e-3", "-9E0"})
    void valid_numbers_are_ranges_of_the_source(String number) {
        var t = new JsonStringTokenizer(" " + number + " ,");
        assertEquals(JsonToken.NUMBER, t.next());
        assertEquals(number, t.currentString());
        assertEquals(JsonToken.VALUE_SEPARATOR, t.next());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-|-",
            "-a|-",
            "-.5|-.5",
            "1.|1.",
            "1.x|1.",
            "1e|1e",
            "1e+|1e+",
            "1.2.3|1.2.3",
            "1e2e3|1e2e3",
            "00|00",
            "-01|-01",
    })
    void invalid_numbers_name_the_whole_run(String json, String run) {
        String message = errorOf(json);
        assertEquals("Invalid JSON number: " + run, message.substring(0, message.indexOf(" (at line")));
    }

    // ----- pending lookahead: the per-character path that a pending peek selects -----

    private static JsonStringTokenizer withPending(char pending, String rest) {
        var t = new JsonStringTokenizer(rest);
        t.peek = pending;
        return t;
    }

    @Test
    void a_pending_digit_starts_a_number_on_the_slow_path() {
        var t = withPending('1', "2.5e+3,");
        assertEquals(JsonToken.NUMBER, t.next());
        assertEquals("12.5e+3", t.currentString());
        assertEquals(JsonToken.VALUE_SEPARATOR, t.next());
    }

    @Test
    void a_pending_minus_reads_a_signed_number_on_the_slow_path() {
        var t = withPending('-', "0.5E2");
        assertEquals(JsonToken.NUMBER, t.next());
        assertEquals("-0.5E2", t.currentString());
        assertEquals(JsonToken.EOF, t.next());
    }

    @Test
    void a_pending_space_is_skipped() {
        var t = withPending(' ', "\n true");
        assertEquals(JsonToken.TRUE, t.next());
        assertEquals(2L, t.line());
    }

    @Test
    void a_pending_non_space_is_the_next_token() {
        var t = withPending('[', "]");
        assertEquals(JsonToken.START_ARRAY, t.next());
        assertEquals(JsonToken.END_ARRAY, t.next());
    }

    @Test
    void slow_path_errors_are_precise() {
        assertTrue(messageOf(withPending('-', "")).contains("Unexpected end after '-'"));
        assertTrue(messageOf(withPending('-', "x")).contains("Expected digit after '-'"));
        assertTrue(messageOf(withPending('0', "1")).contains("Leading zeros"));
        assertTrue(messageOf(withPending('1', ".x")).contains("Expected digit after '.'"));
        assertTrue(messageOf(withPending('1', "e+")).contains("Expected digit in exponent"));
    }

    private static String messageOf(JsonStringTokenizer t) {
        return assertThrows(JsonException.class, t::next).getMessage();
    }

    @Test
    void a_null_source_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new JsonStringTokenizer(null));
    }
}
