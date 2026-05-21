package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Protobuf} et {@link io.vidocq.champollion.protobuf.internal.RuntimeBinding}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/">Proto3 Language Guide</a>
 * et <a href="https://protobuf.dev/programming-guides/encoding/">Encoding</a>.</p>
 *
 * <p>Vecteurs construits manuellement via {@link CodedOutputStream} déjà
 * validé en M1.1 — differential testing contre {@code protoc --encode} sera
 * branché en M1.6 (conformance suite officielle).</p>
 */
class ProtobufRuntimeTest {

    @ProtobufMessage
    public record Person(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            @ProtobufField(number = 2, type = FieldType.INT32) int age,
            @ProtobufField(number = 3, type = FieldType.STRING) List<String> tags
    ) implements Message {}

    @ProtobufMessage
    public record Address(
            @ProtobufField(number = 1, type = FieldType.STRING) String street,
            @ProtobufField(number = 2, type = FieldType.STRING) String city
    ) implements Message {}

    @ProtobufMessage
    public record Employee(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) Address address
    ) implements Message {}

    @ProtobufMessage
    public record PackedScores(
            @ProtobufField(number = 1, type = FieldType.INT32) List<Integer> values
    ) implements Message {}

    public enum Role { USER, ADMIN, SUPERUSER }

    @ProtobufMessage
    public record AccountRecord(
            @ProtobufField(number = 1, type = FieldType.STRING) String login,
            @ProtobufField(number = 2, type = FieldType.ENUM) Role role
    ) implements Message {}

    private byte[] expected(Encoder fn) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        fn.run(out);
        out.flush();
        return baos.toByteArray();
    }

    @FunctionalInterface
    interface Encoder {
        void run(CodedOutputStream out) throws IOException;
    }

    @Nested
    @DisplayName("Sérialisation — Person simple")
    class Serialize {

        @Test
        void person_with_all_fields_set() throws IOException {
            Person p = new Person("alice", 30, List.of("dev", "ops"));
            byte[] actual = Protobuf.toByteArray(p);
            byte[] expected = expected(o -> {
                o.writeString(1, "alice");
                o.writeInt32(2, 30);
                o.writeString(3, "dev");
                o.writeString(3, "ops");
            });
            assertArrayEquals(expected, actual);
        }

        @Test
        void person_with_default_int_omits_field_proto3() throws IOException {
            Person p = new Person("bob", 0, List.of());
            byte[] actual = Protobuf.toByteArray(p);
            // proto3 implicit presence : age=0 et tags vide n'apparaissent pas.
            byte[] expected = expected(o -> o.writeString(1, "bob"));
            assertArrayEquals(expected, actual);
        }

        @Test
        void empty_person_serializes_to_empty_bytes() throws IOException {
            Person p = new Person("", 0, List.of());
            assertArrayEquals(new byte[0], Protobuf.toByteArray(p));
        }
    }

    @Nested
    @DisplayName("Désérialisation — Person roundtrip")
    class Deserialize {

        @Test
        void parseFrom_roundtrip() throws IOException {
            Person original = new Person("alice", 30, List.of("dev", "ops"));
            byte[] bytes = Protobuf.toByteArray(original);
            Person back = Protobuf.parser(Person.class).parseFrom(bytes);
            assertEquals(original, back);
        }

        @Test
        void absent_fields_get_proto3_defaults() throws IOException {
            // bytes contiennent uniquement le champ name
            byte[] bytes = expected(o -> o.writeString(1, "bob"));
            Person back = Protobuf.parser(Person.class).parseFrom(bytes);
            assertEquals("bob", back.name());
            assertEquals(0, back.age());
            assertEquals(List.of(), back.tags());
        }

        @Test
        void message_default_methods_delegate_to_helper() throws IOException {
            Person p = new Person("alice", 30, List.of());
            byte[] viaMessage = p.toByteArray();
            byte[] viaHelper = Protobuf.toByteArray(p);
            assertArrayEquals(viaHelper, viaMessage);
            assertEquals(viaHelper.length, p.getSerializedSize());
        }
    }

    @Nested
    @DisplayName("Embedded message")
    class Embedded {

        @Test
        void employee_with_nested_address_roundtrip() throws IOException {
            Employee e = new Employee("alice", new Address("1 rue", "Paris"));
            byte[] bytes = Protobuf.toByteArray(e);
            Employee back = Protobuf.parser(Employee.class).parseFrom(bytes);
            assertEquals(e, back);
        }

        @Test
        void employee_with_default_nested_address() throws IOException {
            // address = null → champ omis sur la wire
            Employee e = new Employee("bob", null);
            byte[] bytes = Protobuf.toByteArray(e);
            Employee back = Protobuf.parser(Employee.class).parseFrom(bytes);
            assertEquals("bob", back.name());
            // proto3 par défaut : null sur le wire reste null après parse
            // (on n'instancie pas un Address vide automatiquement)
        }
    }

    @Nested
    @DisplayName("Packed repeated — proto3 par défaut")
    class Packed {

        @Test
        void packed_int32_roundtrip() throws IOException {
            PackedScores s = new PackedScores(List.of(3, 270, 86942));
            byte[] bytes = Protobuf.toByteArray(s);
            // Tag (field 1, LEN) | length | payload concaténé
            // 03 8E 02 9E A7 05 → 6 octets payload
            byte[] expected = expected(o -> {
                o.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                o.writeRawVarint32(6);
                o.writeInt32NoTag(3);
                o.writeInt32NoTag(270);
                o.writeInt32NoTag(86942);
            });
            assertArrayEquals(expected, bytes);

            PackedScores back = Protobuf.parser(PackedScores.class).parseFrom(bytes);
            assertEquals(s, back);
        }

        @Test
        void packed_reader_tolerates_expanded_input() throws IOException {
            // Spec §Packed : un lecteur de packed DOIT accepter les deux encodages.
            byte[] expanded = expected(o -> {
                o.writeInt32(1, 3);
                o.writeInt32(1, 270);
                o.writeInt32(1, 86942);
            });
            PackedScores back = Protobuf.parser(PackedScores.class).parseFrom(expanded);
            assertEquals(List.of(3, 270, 86942), back.values());
        }
    }

    @Nested
    @DisplayName("Enum mapping — ordinal-based proto3")
    class EnumMapping {

        @Test
        void enum_roundtrip() throws IOException {
            AccountRecord a = new AccountRecord("root", Role.ADMIN);
            byte[] bytes = Protobuf.toByteArray(a);
            AccountRecord back = Protobuf.parser(AccountRecord.class).parseFrom(bytes);
            assertEquals(a, back);
            assertSame(Role.ADMIN, back.role());
        }

        @Test
        void enum_default_constant_is_first() throws IOException {
            // role absent → premier constant (USER) selon défaut proto3
            byte[] bytes = expected(o -> o.writeString(1, "guest"));
            AccountRecord back = Protobuf.parser(AccountRecord.class).parseFrom(bytes);
            assertSame(Role.USER, back.role());
        }
    }
}
