package io.vidocq.champollion.protobuf.conformance;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * Mapping Java des messages définis dans {@code conformance.proto} Google.
 *
 * <p>Source upstream :
 * <a href="https://github.com/protocolbuffers/protobuf/blob/main/conformance/conformance.proto">conformance.proto</a>.
 * Numéros et types repris à l'identique (les valeurs des oneof se distinguent
 * uniquement par leur {@code field_number}).</p>
 */
public final class ConformanceMessages {

    private ConformanceMessages() {}

    public enum WireFormat { UNSPECIFIED, PROTOBUF, JSON, JSPB, TEXT_FORMAT }

    public enum TestCategory {
        UNSPECIFIED_TEST,
        BINARY_TEST,
        JSON_TEST,
        JSON_IGNORE_UNKNOWN_PARSING_TEST,
        JSPB_TEST,
        TEXT_FORMAT_TEST
    }

    /**
     * {@code conformance.ConformanceRequest}. Le {@code oneof payload} est
     * représenté par 4 champs ; un seul est non-default à la fois.
     */
    @ProtobufMessage
    public record ConformanceRequest(
            @ProtobufField(number = 1, type = FieldType.BYTES) byte[] protobuf_payload,
            @ProtobufField(number = 2, type = FieldType.STRING) String json_payload,
            @ProtobufField(number = 3, type = FieldType.ENUM) WireFormat requested_output_format,
            @ProtobufField(number = 4, type = FieldType.STRING) String message_type,
            @ProtobufField(number = 5, type = FieldType.ENUM) TestCategory test_category,
            @ProtobufField(number = 7, type = FieldType.STRING) String jspb_payload,
            @ProtobufField(number = 8, type = FieldType.STRING) String text_payload,
            @ProtobufField(number = 9, type = FieldType.BOOL) boolean print_unknown_fields
    ) implements Message {
        public boolean hasProtobufPayload() {
            return protobuf_payload != null && protobuf_payload.length > 0;
        }
        public boolean hasJsonPayload() {
            return json_payload != null && !json_payload.isEmpty();
        }
        public boolean hasJspbPayload() {
            return jspb_payload != null && !jspb_payload.isEmpty();
        }
        public boolean hasTextPayload() {
            return text_payload != null && !text_payload.isEmpty();
        }
    }

    /**
     * {@code conformance.ConformanceResponse}. Le {@code oneof result} est
     * représenté par 7 champs (un seul à la fois). On utilise les factories
     * statiques pour produire une réponse cohérente.
     */
    @ProtobufMessage
    public record ConformanceResponse(
            @ProtobufField(number = 1, type = FieldType.STRING) String parse_error,
            @ProtobufField(number = 2, type = FieldType.STRING) String runtime_error,
            @ProtobufField(number = 3, type = FieldType.BYTES) byte[] protobuf_payload,
            @ProtobufField(number = 4, type = FieldType.STRING) String json_payload,
            @ProtobufField(number = 5, type = FieldType.STRING) String skipped,
            @ProtobufField(number = 6, type = FieldType.STRING) String serialize_error,
            @ProtobufField(number = 7, type = FieldType.STRING) String jspb_payload,
            @ProtobufField(number = 8, type = FieldType.STRING) String text_payload
    ) implements Message {

        public static ConformanceResponse parseError(String msg) {
            return new ConformanceResponse(msg, "", new byte[0], "", "", "", "", "");
        }

        public static ConformanceResponse runtimeError(String msg) {
            return new ConformanceResponse("", msg, new byte[0], "", "", "", "", "");
        }

        public static ConformanceResponse serializeError(String msg) {
            return new ConformanceResponse("", "", new byte[0], "", "", msg, "", "");
        }

        public static ConformanceResponse protobufPayload(byte[] bytes) {
            return new ConformanceResponse("", "", bytes, "", "", "", "", "");
        }

        public static ConformanceResponse jsonPayload(String json) {
            return new ConformanceResponse("", "", new byte[0], json, "", "", "", "");
        }

        public static ConformanceResponse skipped(String reason) {
            return new ConformanceResponse("", "", new byte[0], "", reason, "", "", "");
        }
    }
}
