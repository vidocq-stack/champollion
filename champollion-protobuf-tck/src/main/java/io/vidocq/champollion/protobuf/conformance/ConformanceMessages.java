package io.vidocq.champollion.protobuf.conformance;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * Java mapping of the messages defined in Google's {@code conformance.proto}.
 *
 * <p>Upstream source:
 * <a href="https://github.com/protocolbuffers/protobuf/blob/main/conformance/conformance.proto">conformance.proto</a>.
 * Field numbers and types are reproduced as-is (oneof values are distinguished
 * solely by their {@code field_number}).</p>
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
     * {@code conformance.ConformanceRequest}. The {@code oneof payload} is
     * represented by 4 fields; only one is non-default at a time.
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
     * {@code conformance.ConformanceResponse}. The original {@code oneof result}
     * is represented by 8 fields; only one is non-{@code null} at a time.
     *
     * <p>{@code explicitPresence=true} on all: required so that
     * <em>empty</em> payloads (e.g. {@code protobuf_payload = new byte[0]} when
     * the encoded protobuf message is entirely default) are not omitted,
     * otherwise the Google runner interprets as "no payload set" and fails with
     * {@code unknown payload type: 0}. The other factories use {@code null}
     * for non-selected fields so that {@code writeMessage} skips them
     * (cf. {@code if (value == null) continue;}).</p>
     */
    @ProtobufMessage
    public record ConformanceResponse(
            @ProtobufField(number = 1, type = FieldType.STRING, explicitPresence = true) String parse_error,
            @ProtobufField(number = 2, type = FieldType.STRING, explicitPresence = true) String runtime_error,
            @ProtobufField(number = 3, type = FieldType.BYTES, explicitPresence = true) byte[] protobuf_payload,
            @ProtobufField(number = 4, type = FieldType.STRING, explicitPresence = true) String json_payload,
            @ProtobufField(number = 5, type = FieldType.STRING, explicitPresence = true) String skipped,
            @ProtobufField(number = 6, type = FieldType.STRING, explicitPresence = true) String serialize_error,
            @ProtobufField(number = 7, type = FieldType.STRING, explicitPresence = true) String jspb_payload,
            @ProtobufField(number = 8, type = FieldType.STRING, explicitPresence = true) String text_payload
    ) implements Message {

        public static ConformanceResponse parseError(String msg) {
            return new ConformanceResponse(msg, null, null, null, null, null, null, null);
        }

        public static ConformanceResponse runtimeError(String msg) {
            return new ConformanceResponse(null, msg, null, null, null, null, null, null);
        }

        public static ConformanceResponse serializeError(String msg) {
            return new ConformanceResponse(null, null, null, null, null, msg, null, null);
        }

        public static ConformanceResponse protobufPayload(byte[] bytes) {
            return new ConformanceResponse(null, null, bytes, null, null, null, null, null);
        }

        public static ConformanceResponse jsonPayload(String json) {
            return new ConformanceResponse(null, null, null, json, null, null, null, null);
        }

        public static ConformanceResponse skipped(String reason) {
            return new ConformanceResponse(null, null, null, null, reason, null, null, null);
        }
    }
}
