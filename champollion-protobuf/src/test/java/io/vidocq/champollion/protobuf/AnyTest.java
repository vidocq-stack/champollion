package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.TypeRegistry;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Any} et {@link TypeRegistry}.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#any">Any</a>.</p>
 */
class AnyTest {

    @ProtobufMessage("test.Item")
    public record Item(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            @ProtobufField(number = 2, type = FieldType.INT32) int qty
    ) implements Message {}

    @Nested
    @DisplayName("pack — construit type_url + sérialise")
    class Pack {

        @Test
        void default_base_typeUrl() {
            Item i = new Item("widget", 5);
            Any any = Any.pack(i);
            assertEquals("type.googleapis.com/test.Item", any.type_url());
            assertArrayEquals(Protobuf.toByteArray(i), any.value());
        }

        @Test
        void custom_base_typeUrl() {
            Any any = Any.pack(new Item("x", 1), "my.registry.local");
            assertEquals("my.registry.local/test.Item", any.type_url());
        }

        @Test
        void non_message_class_throws() {
            record Plain(String x) {}
            assertThrows(IllegalArgumentException.class, () -> Any.pack(new Plain("x")));
        }

        @Test
        void pack_then_typed_unpack_roundtrip() throws IOException {
            Item original = new Item("alice", 42);
            Item back = Any.pack(original).unpack(Item.class);
            assertEquals(original, back);
        }
    }

    @Nested
    @DisplayName("unpack — type_url vs Class")
    class Unpack {

        @Test
        void typed_unpack_with_wrong_class_throws() {
            Any any = Any.pack(new Item("x", 1));
            assertThrows(IllegalStateException.class, () -> any.unpack(Timestamp.class));
        }

        @Test
        void isA_predicate() {
            Any any = Any.pack(new Item("x", 1));
            assertTrue(any.isA(Item.class));
            assertTrue(!any.isA(Timestamp.class));
        }

        @Test
        void registry_unpack_returns_concrete_type() throws IOException {
            TypeRegistry.register(Item.class);
            Any any = Any.pack(new Item("alice", 42));
            Object back = any.unpack();
            assertInstanceOf(Item.class, back);
            assertEquals(new Item("alice", 42), back);
        }

        @Test
        void registry_unpack_returns_null_for_unknown_type() throws IOException {
            Any any = new Any("type.googleapis.com/unknown.NeverRegistered",
                    new byte[]{0x01, 0x02});
            assertNull(any.unpack());
        }
    }

    @Nested
    @DisplayName("TypeRegistry")
    class Registry {

        @Test
        void wkt_auto_registered() {
            assertEquals(Timestamp.class, TypeRegistry.lookup("google.protobuf.Timestamp"));
            assertNotNull(TypeRegistry.lookup("google.protobuf.Duration"));
            assertNotNull(TypeRegistry.lookup("google.protobuf.Empty"));
            assertNotNull(TypeRegistry.lookup("google.protobuf.FieldMask"));
            assertNotNull(TypeRegistry.lookup("google.protobuf.Int64Value"));
        }

        @Test
        void typeUrlFor_uses_default_base() {
            assertEquals("type.googleapis.com/google.protobuf.Timestamp",
                    TypeRegistry.typeUrlFor(Timestamp.class));
        }

        @Test
        void fullNameFromTypeUrl_strips_base() {
            assertEquals("google.protobuf.Timestamp",
                    TypeRegistry.fullNameFromTypeUrl("type.googleapis.com/google.protobuf.Timestamp"));
            assertEquals("plain.Name",
                    TypeRegistry.fullNameFromTypeUrl("plain.Name"));
        }

        @Test
        void manual_register_then_lookup() {
            TypeRegistry.register("custom.my.Type", Item.class);
            assertEquals(Item.class, TypeRegistry.lookup("custom.my.Type"));
        }
    }

    @Nested
    @DisplayName("Roundtrip wire format")
    class WireRoundtrip {

        @Test
        void any_serializes_and_parses() throws IOException {
            Any original = Any.pack(new Item("widget", 5));
            byte[] bytes = Protobuf.toByteArray(original);
            Any back = Protobuf.parser(Any.class).parseFrom(bytes);
            assertEquals(original, back);
        }

        @Test
        void any_containing_timestamp() throws IOException {
            Timestamp ts = Timestamp.from(java.time.Instant.parse("2026-05-21T10:00:00Z"));
            Any any = Any.pack(ts);
            assertEquals("type.googleapis.com/google.protobuf.Timestamp", any.type_url());
            Timestamp back = any.unpack(Timestamp.class);
            assertEquals(ts, back);
        }
    }
}
