package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonValueImplTest {

    @Nested
    @DisplayName("ChampollionJsonString — Jakarta JSON-P §4.4")
    class StringValue {

        @Test
        void value_type_and_getString() {
            var s = new ChampollionJsonString("hello");
            assertEquals(JsonValue.ValueType.STRING, s.getValueType());
            assertEquals("hello", s.getString());
        }

        @Test
        void getChars() {
            var s = new ChampollionJsonString("abc");
            assertEquals("abc", s.getChars().toString());
        }

        @Test
        void equals_and_hashCode_by_value() {
            var a = new ChampollionJsonString("x");
            var b = new ChampollionJsonString("x");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, new ChampollionJsonString("y"));
        }

        @Test
        void toString_returns_quoted_escaped_form() {
            var s = new ChampollionJsonString("a\"b\nc");
            assertEquals("\"a\\\"b\\nc\"", s.toString());
        }
    }

    @Nested
    @DisplayName("ChampollionJsonNumber — Jakarta JSON-P §4.5")
    class NumberValue {

        @Test
        void integer_classification() {
            var n = ChampollionJsonNumber.of("42");
            assertEquals(JsonValue.ValueType.NUMBER, n.getValueType());
            assertTrue(n.isIntegral());
            assertEquals(42, n.intValue());
            assertEquals(42L, n.longValue());
            assertEquals(BigInteger.valueOf(42), n.bigIntegerValue());
            assertEquals(new BigDecimal("42"), n.bigDecimalValue());
            assertEquals(42.0, n.doubleValue(), 0.0);
        }

        @Test
        void decimal_classification() {
            var n = ChampollionJsonNumber.of("3.14");
            assertFalse(n.isIntegral());
            assertEquals(new BigDecimal("3.14"), n.bigDecimalValue());
        }

        @Test
        void exponent_form_is_not_integral_even_when_value_is_integer() {
            // 1e2 == 100 mais l'écriture conserve la forme exponentielle.
            var n = ChampollionJsonNumber.of("1e2");
            assertFalse(n.isIntegral());
            assertEquals(100.0, n.doubleValue(), 0.0);
        }

        @Test
        void intValueExact_throws_on_overflow() {
            var n = ChampollionJsonNumber.of("999999999999999");
            assertThrows(ArithmeticException.class, n::intValueExact);
        }

        @Test
        void bigIntegerValueExact_throws_on_decimal() {
            var n = ChampollionJsonNumber.of("1.5");
            assertThrows(ArithmeticException.class, n::bigIntegerValueExact);
        }

        @Test
        void toString_preserves_lexical_form() {
            assertEquals("42", ChampollionJsonNumber.of("42").toString());
            assertEquals("3.14", ChampollionJsonNumber.of("3.14").toString());
            assertEquals("-1.5e-3", ChampollionJsonNumber.of("-1.5e-3").toString());
        }

        @Test
        void equals_compares_numerical_value_per_spec() {
            // Spec §4.5 : equals based on bigDecimalValue() — "1" and "1.0" are numerically equal.
            var a = ChampollionJsonNumber.of("1");
            var b = ChampollionJsonNumber.of("1.0");
            assertEquals(a, b);
        }
    }

    @Nested
    @DisplayName("ChampollionJsonArray — §4.2")
    class ArrayValue {

        @Test
        void empty_array_is_singleton_like() {
            var a = ChampollionJsonArray.of(List.of());
            assertEquals(JsonValue.ValueType.ARRAY, a.getValueType());
            assertEquals(0, a.size());
            assertTrue(a.isEmpty());
        }

        @Test
        void array_with_values() {
            var a = ChampollionJsonArray.of(List.of(
                    new ChampollionJsonString("x"),
                    ChampollionJsonNumber.of("1"),
                    JsonValue.TRUE
            ));
            assertEquals(3, a.size());
            assertEquals(new ChampollionJsonString("x"), a.get(0));
            assertEquals(JsonValue.TRUE, a.get(2));
        }

        @Test
        void array_is_immutable() {
            var a = ChampollionJsonArray.of(List.of(JsonValue.TRUE));
            assertThrows(UnsupportedOperationException.class, () -> a.add(JsonValue.FALSE));
            assertThrows(UnsupportedOperationException.class, () -> a.remove(0));
        }

        @Test
        void toString_serializes_to_compact_json() {
            var a = ChampollionJsonArray.of(List.of(
                    ChampollionJsonNumber.of("1"),
                    new ChampollionJsonString("x"),
                    JsonValue.NULL
            ));
            assertEquals("[1,\"x\",null]", a.toString());
        }
    }

    @Nested
    @DisplayName("ChampollionJsonObject — §4.1")
    class ObjectValue {

        @Test
        void preserves_insertion_order() {
            var members = new LinkedHashMap<String, JsonValue>();
            members.put("a", ChampollionJsonNumber.of("1"));
            members.put("b", JsonValue.TRUE);
            var o = ChampollionJsonObject.of(members);
            assertEquals(List.of("a", "b"), List.copyOf(o.keySet()));
        }

        @Test
        void getString_helper_extracts_value() {
            var o = ChampollionJsonObject.of(java.util.Map.of(
                    "name", new ChampollionJsonString("Alice")
            ));
            assertEquals("Alice", o.getString("name"));
        }

        @Test
        void object_is_immutable() {
            var o = ChampollionJsonObject.of(java.util.Map.of("a", JsonValue.TRUE));
            assertThrows(UnsupportedOperationException.class, () -> o.put("b", JsonValue.FALSE));
        }

        @Test
        void toString_serializes_to_compact_json() {
            var members = new LinkedHashMap<String, JsonValue>();
            members.put("a", ChampollionJsonNumber.of("1"));
            members.put("b", new ChampollionJsonString("x"));
            var o = ChampollionJsonObject.of(members);
            assertEquals("{\"a\":1,\"b\":\"x\"}", o.toString());
        }
    }

    @Test
    void true_false_null_constants_resolve_to_spec_singletons() {
        assertSame(JsonValue.TRUE, JsonValue.TRUE);
        assertSame(JsonValue.FALSE, JsonValue.FALSE);
        assertSame(JsonValue.NULL, JsonValue.NULL);
    }
}
