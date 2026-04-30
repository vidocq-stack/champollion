package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTokenizerTest {

    private static List<JsonToken> tokenize(String input) {
        var t = new JsonTokenizer(new StringReader(input));
        var out = new ArrayList<JsonToken>();
        while (true) {
            var tok = t.next();
            out.add(tok);
            if (tok == JsonToken.Eof.INSTANCE) return out;
        }
    }

    @Nested
    @DisplayName("RFC 8259 §2 — structural characters")
    class Structural {

        @Test
        void recognizes_braces_brackets_separators() {
            var tokens = tokenize("{}[]:,");
            assertEquals(7, tokens.size());
            assertEquals(JsonToken.StartObject.INSTANCE, tokens.get(0));
            assertEquals(JsonToken.EndObject.INSTANCE, tokens.get(1));
            assertEquals(JsonToken.StartArray.INSTANCE, tokens.get(2));
            assertEquals(JsonToken.EndArray.INSTANCE, tokens.get(3));
            assertEquals(JsonToken.NameSeparator.INSTANCE, tokens.get(4));
            assertEquals(JsonToken.ValueSeparator.INSTANCE, tokens.get(5));
            assertEquals(JsonToken.Eof.INSTANCE, tokens.get(6));
        }

        @Test
        void skips_all_whitespace_kinds_rfc8259_section2() {
            // RFC 8259 §2 : ws = *(%x20 / %x09 / %x0A / %x0D)
            var tokens = tokenize(" \t\n\r {  }\n");
            assertEquals(JsonToken.StartObject.INSTANCE, tokens.get(0));
            assertEquals(JsonToken.EndObject.INSTANCE, tokens.get(1));
            assertEquals(JsonToken.Eof.INSTANCE, tokens.get(2));
        }

        @Test
        void empty_input_yields_eof_only() {
            assertEquals(List.of(JsonToken.Eof.INSTANCE), tokenize(""));
            assertEquals(List.of(JsonToken.Eof.INSTANCE), tokenize("   "));
        }
    }

    @Nested
    @DisplayName("RFC 8259 §3 — keywords")
    class Keywords {

        @Test
        void recognizes_true_false_null() {
            var tokens = tokenize("true false null");
            assertEquals(JsonToken.True.INSTANCE, tokens.get(0));
            assertEquals(JsonToken.False.INSTANCE, tokens.get(1));
            assertEquals(JsonToken.Null.INSTANCE, tokens.get(2));
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
            assertEquals("0", literalOf(tokenize("0").get(0)));
            assertEquals("-0", literalOf(tokenize("-0").get(0)));
            assertEquals("42", literalOf(tokenize("42").get(0)));
            assertEquals("-42", literalOf(tokenize("-42").get(0)));
            assertEquals("123456789", literalOf(tokenize("123456789").get(0)));
        }

        @Test
        void fractions_and_exponents() {
            assertEquals("3.14", literalOf(tokenize("3.14").get(0)));
            assertEquals("1e10", literalOf(tokenize("1e10").get(0)));
            assertEquals("1E+10", literalOf(tokenize("1E+10").get(0)));
            assertEquals("-1.5e-3", literalOf(tokenize("-1.5e-3").get(0)));
            assertEquals("0.5", literalOf(tokenize("0.5").get(0)));
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

        private String literalOf(JsonToken t) {
            return ((JsonToken.NumberToken) t).literal();
        }
    }

    @Nested
    @DisplayName("RFC 8259 §7 — strings")
    class Strings {

        @Test
        void empty_and_simple_string() {
            assertEquals("", valueOf(tokenize("\"\"").get(0)));
            assertEquals("hello", valueOf(tokenize("\"hello\"").get(0)));
        }

        @Test
        void escape_sequences() {
            assertEquals("\"", valueOf(tokenize("\"\\\"\"").get(0)));
            assertEquals("\\", valueOf(tokenize("\"\\\\\"").get(0)));
            assertEquals("/", valueOf(tokenize("\"\\/\"").get(0)));
            assertEquals("\b\f\n\r\t", valueOf(tokenize("\"\\b\\f\\n\\r\\t\"").get(0)));
        }

        @Test
        void unicode_escape() {
            assertEquals("A", valueOf(tokenize("\"\\u0041\"").get(0)));
            assertEquals("é", valueOf(tokenize("\"\\u00E9\"").get(0)));
            // mixed casing
            assertEquals("«", valueOf(tokenize("\"\\u00aB\"").get(0)));
        }

        @Test
        void rejects_unterminated_string() {
            assertThrows(JsonException.class, () -> tokenize("\"hello"));
        }

        @Test
        void rejects_unescaped_control_character() {
            // RFC 8259 §7 : control characters U+0000..U+001F MUST be escaped
            assertThrows(JsonException.class, () -> tokenize("\"a\nb\""));
            assertThrows(JsonException.class, () -> tokenize("\"\""));
        }

        @Test
        void rejects_invalid_escape() {
            assertThrows(JsonException.class, () -> tokenize("\"\\x\""));
            assertThrows(JsonException.class, () -> tokenize("\"\\u00G0\""));
            assertThrows(JsonException.class, () -> tokenize("\"\\u00\""));
        }

        private String valueOf(JsonToken t) {
            return ((JsonToken.StringToken) t).value();
        }
    }

    @Nested
    @DisplayName("Composite inputs")
    class Composite {

        @Test
        void object_with_one_member() {
            var tokens = tokenize("{\"a\":1}");
            assertEquals(JsonToken.StartObject.INSTANCE, tokens.get(0));
            assertInstanceOf(JsonToken.StringToken.class, tokens.get(1));
            assertEquals(JsonToken.NameSeparator.INSTANCE, tokens.get(2));
            assertInstanceOf(JsonToken.NumberToken.class, tokens.get(3));
            assertEquals(JsonToken.EndObject.INSTANCE, tokens.get(4));
            assertEquals(JsonToken.Eof.INSTANCE, tokens.get(5));
        }

        @Test
        void array_with_mixed_values() {
            var tokens = tokenize("[1,\"x\",true,null]");
            assertEquals(JsonToken.StartArray.INSTANCE, tokens.get(0));
            assertInstanceOf(JsonToken.NumberToken.class, tokens.get(1));
            assertEquals(JsonToken.ValueSeparator.INSTANCE, tokens.get(2));
            assertInstanceOf(JsonToken.StringToken.class, tokens.get(3));
            assertEquals(JsonToken.ValueSeparator.INSTANCE, tokens.get(4));
            assertEquals(JsonToken.True.INSTANCE, tokens.get(5));
            assertEquals(JsonToken.ValueSeparator.INSTANCE, tokens.get(6));
            assertEquals(JsonToken.Null.INSTANCE, tokens.get(7));
            assertEquals(JsonToken.EndArray.INSTANCE, tokens.get(8));
        }
    }

    @Test
    void rejects_unexpected_character() {
        assertThrows(JsonException.class, () -> tokenize("@"));
        assertThrows(JsonException.class, () -> tokenize("'single quotes'"));
    }
}
