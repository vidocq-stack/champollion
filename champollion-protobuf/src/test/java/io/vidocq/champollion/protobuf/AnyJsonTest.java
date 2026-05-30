package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Duration;
import io.vidocq.champollion.protobuf.wkt.Empty;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.TypeRegistry;
import io.vidocq.champollion.protobuf.wkt.Wrappers;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests Proto3 JSON Canonical Mapping pour {@link Any}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 §JSON Mapping</a>,
 * section Any. Le canonical exige :</p>
 * <ul>
 *   <li>Pour un Any wrappant un WKT : {@code {"@type":"...", "value":<wkt>}}</li>
 *   <li>Pour un Any wrappant un message ordinaire : {@code {"@type":"...", ...champs}}</li>
 * </ul>
 */
class AnyJsonTest {

    @ProtobufMessage("test.Item")
    public record Item(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            @ProtobufField(number = 2, type = FieldType.INT32) int qty
    ) implements Message {}

    @Nested
    @DisplayName("Write — aplatissement message ordinaire / value pour WKT")
    class Write {

        @Test
        void any_with_ordinary_message_flattens_fields() {
            TypeRegistry.register(Item.class);
            Any any = Any.pack(new Item("widget", 5));
            String json = ProtobufJson.toJson(any);
            // Canonical : @type + champs aplatis
            assertTrue(json.contains("\"@type\":\"type.googleapis.com/test.Item\""), json);
            assertTrue(json.contains("\"name\":\"widget\""), json);
            assertTrue(json.contains("\"qty\":5"), json);
            // PAS de "value":"<base64>" — c'est le mode fallback uniquement
            assertTrue(!json.contains("\"value\":\""), "Should not use value-base64 fallback: " + json);
        }

        @Test
        void any_with_timestamp_uses_value_field() {
            Any any = Any.pack(Timestamp.from(Instant.parse("2026-05-21T10:00:00Z")));
            String json = ProtobufJson.toJson(any);
            assertTrue(json.contains("\"@type\":\"type.googleapis.com/google.protobuf.Timestamp\""), json);
            assertTrue(json.contains("\"value\":\"2026-05-21T10:00:00Z\""), json);
        }

        @Test
        void any_with_duration_uses_value_field() {
            Any any = Any.pack(new Duration(3, 500_000_000));
            String json = ProtobufJson.toJson(any);
            assertTrue(json.contains("\"value\":\"3.500s\""), json);
        }

        @Test
        void any_with_int64_wrapper_uses_value_field() {
            Any any = Any.pack(new Wrappers.Int64Value(9_007_199_254_740_993L));
            String json = ProtobufJson.toJson(any);
            assertTrue(json.contains("\"@type\":\"type.googleapis.com/google.protobuf.Int64Value\""), json);
            assertTrue(json.contains("\"value\":\"9007199254740993\""), json);
        }

        @Test
        void any_with_empty_omits_value_field() {
            // Spec proto3 §any : Any wrappant Empty n'émet PAS le champ "value" —
            // juste @type. (Le test M6.7 conformance AnyEmpty.JsonOutput).
            Any any = Any.pack(Empty.INSTANCE);
            String json = ProtobufJson.toJson(any);
            assertEquals(
                    "\"type.googleapis.com/google.protobuf.Empty\"",
                    json.split("\"@type\":")[1].split("[},]")[0].replaceAll("\\s", ""),
                    json);
            assertTrue(!json.contains("\"value\""), "Empty Any must not have value field: " + json);
        }

        @Test
        void unknown_type_falls_back_to_base64() {
            // Type non enregistré dans la registry
            Any any = new Any("type.googleapis.com/unknown.Mystery", new byte[]{1, 2, 3});
            String json = ProtobufJson.toJson(any);
            assertTrue(json.contains("\"@type\":\"type.googleapis.com/unknown.Mystery\""), json);
            assertTrue(json.contains("\"value\":\"AQID\""), json);
        }
    }

    @Nested
    @DisplayName("Read — round-trip via @type lookup")
    class Read {

        @Test
        void roundtrip_ordinary_message() {
            TypeRegistry.register(Item.class);
            Any original = Any.pack(new Item("widget", 5));
            String json = ProtobufJson.toJson(original);
            Any back = ProtobufJson.fromJson(Any.class, json);
            assertEquals(original, back);
        }

        @Test
        void roundtrip_timestamp() {
            Any original = Any.pack(Timestamp.from(Instant.parse("2026-05-21T10:00:00.123Z")));
            String json = ProtobufJson.toJson(original);
            Any back = ProtobufJson.fromJson(Any.class, json);
            assertEquals(original, back);
        }

        @Test
        void roundtrip_unknown_falls_back_to_base64() {
            Any original = new Any("type.googleapis.com/unknown.X", new byte[]{1, 2, 3});
            String json = ProtobufJson.toJson(original);
            Any back = ProtobufJson.fromJson(Any.class, json);
            assertEquals(original, back);
        }

        @Test
        void atType_order_does_not_matter() throws Exception {
            // JSON est non-ordonné — @type peut être au milieu
            TypeRegistry.register(Item.class);
            String json = "{\"name\":\"x\",\"@type\":\"type.googleapis.com/test.Item\",\"qty\":7}";
            Any any = ProtobufJson.fromJson(Any.class, json);
            assertEquals("type.googleapis.com/test.Item", any.type_url());
            // Decode value bytes to verify the fields
            assertEquals(new Item("x", 7), Protobuf.parser(Item.class).parseFrom(any.value()));
        }
    }
}
