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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ProtobufJson} — Proto3 JSON Canonical Mapping.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 §JSON Mapping</a>.</p>
 */
class ProtobufJsonTest {

    @ProtobufMessage
    public record AllScalars(
            @ProtobufField(number = 1, type = FieldType.INT32) int i32,
            @ProtobufField(number = 2, type = FieldType.INT64) long i64,
            @ProtobufField(number = 3, type = FieldType.BOOL) boolean b,
            @ProtobufField(number = 4, type = FieldType.STRING) String s,
            @ProtobufField(number = 5, type = FieldType.BYTES) byte[] bs,
            @ProtobufField(number = 6, type = FieldType.DOUBLE) double d
    ) implements Message {
        @Override
        public boolean equals(Object o) {
            return o instanceof AllScalars a
                    && i32 == a.i32 && i64 == a.i64 && b == a.b
                    && java.util.Objects.equals(s, a.s)
                    && java.util.Arrays.equals(bs, a.bs)
                    && Double.compare(d, a.d) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(i32, i64, b, s, d) ^ java.util.Arrays.hashCode(bs);
        }
    }

    @ProtobufMessage
    public record Snake(
            @ProtobufField(number = 1, type = FieldType.STRING) String first_name,
            @ProtobufField(number = 2, type = FieldType.INT32) int age_in_years
    ) implements Message {}

    public enum Role { USER, ADMIN }

    @ProtobufMessage
    public record Account(
            @ProtobufField(number = 1, type = FieldType.STRING) String login,
            @ProtobufField(number = 2, type = FieldType.ENUM) Role role
    ) implements Message {}

    @ProtobufMessage
    public record Container(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Account account,
            @ProtobufField(number = 2, type = FieldType.INT32) List<Integer> nums
    ) implements Message {}

    @Nested
    @DisplayName("Scalaires — encodage canonical")
    class Scalars {

        @Test
        void int64_is_emitted_as_string() {
            AllScalars a = new AllScalars(7, 9_007_199_254_740_993L, true, "x", new byte[]{1, 2}, 3.14);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"i64\":\"9007199254740993\""),
                    "int64 doit être en string canonical, got " + json);
            assertTrue(json.contains("\"i32\":7"),
                    "int32 reste un number, got " + json);
        }

        @Test
        void bytes_are_base64() {
            AllScalars a = new AllScalars(0, 0L, false, "", new byte[]{1, 2, 3}, 0.0);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"bs\":\"AQID\""),
                    "bytes doivent être en base64 canonical, got " + json);
        }

        @Test
        void default_proto3_values_omitted() {
            AllScalars empty = new AllScalars(0, 0L, false, "", new byte[0], 0.0);
            String json = ProtobufJson.toJson(empty);
            // proto3 implicit presence : tous les défauts sont omis
            assertEquals("{}", json);
        }

        @Test
        void double_NaN_emitted_as_string() {
            AllScalars a = new AllScalars(0, 0L, false, "", new byte[0], Double.NaN);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"d\":\"NaN\""), "NaN doit être en string, got " + json);
        }

        @Test
        void roundtrip_preserves_scalars() {
            AllScalars original = new AllScalars(42, 12345L, true, "hello",
                    new byte[]{10, 20, 30}, 2.71828);
            String json = ProtobufJson.toJson(original);
            AllScalars back = ProtobufJson.fromJson(AllScalars.class, json);
            assertEquals(original, back);
        }

        @Test
        void int64_string_AND_number_accepted_lenient() {
            // String canonical
            AllScalars a1 = ProtobufJson.fromJson(AllScalars.class,
                    "{\"i64\":\"42\"}");
            assertEquals(42L, a1.i64());
            // Number lenient (non canonical mais accepté)
            AllScalars a2 = ProtobufJson.fromJson(AllScalars.class,
                    "{\"i64\":42}");
            assertEquals(42L, a2.i64());
        }
    }

    @Nested
    @DisplayName("Noms — snake_case → camelCase canonical")
    class Naming {

        @Test
        void writes_in_camelCase() {
            Snake s = new Snake("alice", 30);
            String json = ProtobufJson.toJson(s);
            assertTrue(json.contains("\"firstName\""), "got " + json);
            assertTrue(json.contains("\"ageInYears\""), "got " + json);
        }

        @Test
        void reads_camelCase_AND_snake_case_lenient() {
            Snake s1 = ProtobufJson.fromJson(Snake.class,
                    "{\"firstName\":\"a\",\"ageInYears\":1}");
            Snake s2 = ProtobufJson.fromJson(Snake.class,
                    "{\"first_name\":\"a\",\"age_in_years\":1}");
            assertEquals(s1, s2);
        }
    }

    @Nested
    @DisplayName("Enum — string name canonical, ordinal lenient")
    class EnumMapping {

        @Test
        void enum_emitted_as_constant_name() {
            Account a = new Account("root", Role.ADMIN);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"role\":\"ADMIN\""), "got " + json);
        }

        @Test
        void enum_read_from_name_OR_ordinal() {
            Account a1 = ProtobufJson.fromJson(Account.class,
                    "{\"login\":\"x\",\"role\":\"ADMIN\"}");
            Account a2 = ProtobufJson.fromJson(Account.class,
                    "{\"login\":\"x\",\"role\":1}");
            assertSame(Role.ADMIN, a1.role());
            assertSame(Role.ADMIN, a2.role());
        }
    }

    @Nested
    @DisplayName("Repeated & nested messages")
    class Nested_ {

        @Test
        void repeated_int32_as_array() {
            Container c = new Container(null, List.of(1, 2, 3));
            String json = ProtobufJson.toJson(c);
            assertTrue(json.contains("\"nums\":[1,2,3]"), "got " + json);
        }

        @Test
        void nested_message_roundtrip() {
            Container c = new Container(new Account("alice", Role.USER), List.of(7));
            String json = ProtobufJson.toJson(c);
            Container back = ProtobufJson.fromJson(Container.class, json);
            assertEquals(c, back);
        }

        @Test
        void empty_repeated_omitted_canonical() {
            Container c = new Container(null, List.of());
            assertEquals("{}", ProtobufJson.toJson(c));
        }
    }

    @Nested
    @DisplayName("Unknown fields — silently dropped")
    class Unknown {

        @Test
        void extra_json_keys_ignored() {
            String json = "{\"login\":\"x\",\"role\":\"USER\",\"future_field\":42,\"another\":{\"nested\":\"yes\"}}";
            Account a = ProtobufJson.fromJson(Account.class, json);
            assertEquals("x", a.login());
            assertSame(Role.USER, a.role());
        }
    }

    @ProtobufMessage
    public record Floats(
            @ProtobufField(number = 1, type = FieldType.FLOAT) float f,
            @ProtobufField(number = 2, type = FieldType.DOUBLE) double d,
            @ProtobufField(number = 3, type = FieldType.FLOAT) List<Float> fs,
            @ProtobufField(number = 4, type = FieldType.DOUBLE) List<Double> ds
    ) implements Message {}

    @ProtobufMessage
    public record Counters(
            @ProtobufField(number = 1, type = FieldType.MAP,
                           mapKey = FieldType.STRING, mapValue = FieldType.INT32)
            java.util.Map<String, Integer> counts
    ) implements Message {}

    @ProtobufMessage
    public record Integers(
            @ProtobufField(number = 1, type = FieldType.INT32) int i32,
            @ProtobufField(number = 2, type = FieldType.UINT32) int u32,
            @ProtobufField(number = 3, type = FieldType.INT64) long i64,
            @ProtobufField(number = 4, type = FieldType.UINT64) long u64
    ) implements Message {}

    @Nested
    @DisplayName("Floating-point specials — \"Infinity\" and \"-Infinity\" strings")
    class FloatingSpecials {

        @Test
        void infinities_are_written_as_strings_in_fields_and_arrays() {
            Floats a = new Floats(Float.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    List.of(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY),
                    List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
            assertEquals("{\"f\":\"Infinity\",\"d\":\"-Infinity\",\"fs\":[\"-Infinity\",\"Infinity\"],"
                    + "\"ds\":[\"Infinity\",\"-Infinity\"]}", ProtobufJson.toJson(a));
            Floats b = new Floats(Float.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, List.of(), List.of());
            assertEquals("{\"f\":\"-Infinity\",\"d\":\"Infinity\"}", ProtobufJson.toJson(b));
        }

        @Test
        void infinities_round_trip() {
            Floats original = new Floats(Float.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    List.of(Float.POSITIVE_INFINITY), List.of(Double.NEGATIVE_INFINITY));
            assertEquals(original, ProtobufJson.fromJson(Floats.class, ProtobufJson.toJson(original)));
        }
    }

    @Nested
    @DisplayName("Maps — JSON object keyed by the map key")
    class Maps {

        @Test
        void map_field_is_written_as_an_object() {
            var counts = new java.util.LinkedHashMap<String, Integer>();
            counts.put("a", 1);
            counts.put("b", 2);
            assertEquals("{\"counts\":{\"a\":1,\"b\":2}}", ProtobufJson.toJson(new Counters(counts)));
        }
    }

    @Nested
    @DisplayName("Integer fields — non-integral input rejected")
    class IntegerRanges {

        @Test
        void fractional_numbers_are_rejected_for_every_integer_type() {
            // Proto3 JSON: integer fields accept integral values only (1.0 is fine, 1.5 is not).
            for (String field : List.of("i32", "u32", "i64", "u64")) {
                String json = "{\"" + field + "\":1.5}";
                assertThrows(java.io.UncheckedIOException.class,
                        () -> ProtobufJson.fromJson(Integers.class, json), field);
            }
        }

        @Test
        void integral_numbers_with_a_fraction_part_are_accepted() {
            Integers i = ProtobufJson.fromJson(Integers.class,
                    "{\"i32\":1.0,\"u32\":2.0,\"i64\":3.0,\"u64\":4.0}");
            assertEquals(new Integers(1, 2, 3L, 4L), i);
        }

        @Test
        void a_string_that_is_not_a_number_is_rejected() {
            assertThrows(java.io.UncheckedIOException.class,
                    () -> ProtobufJson.fromJson(Integers.class, "{\"i32\":\"abc\"}"));
        }
    }

    @Nested
    @DisplayName("Sinks — Writer and OutputStream")
    class Sinks {

        @Test
        void writes_to_a_writer() {
            var out = new java.io.StringWriter();
            ProtobufJson.toJson(new Snake("Ada", 36), out);
            assertEquals("{\"firstName\":\"Ada\",\"ageInYears\":36}", out.toString());
        }

        @Test
        void writes_utf8_to_an_output_stream() {
            var out = new java.io.ByteArrayOutputStream();
            ProtobufJson.toJson(new Snake("Zoé", 1), out);
            assertEquals("{\"firstName\":\"Zoé\",\"ageInYears\":1}",
                    out.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
