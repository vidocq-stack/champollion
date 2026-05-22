package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
}
