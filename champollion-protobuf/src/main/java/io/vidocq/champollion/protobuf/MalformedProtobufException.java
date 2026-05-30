package io.vidocq.champollion.protobuf;

import java.io.IOException;

/**
 * Thrown when the binary stream does not respect the Protocol Buffers wire format
 * (varint > 10 bytes, unknown wire type, negative LEN length, truncation, etc.).
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 */
public final class MalformedProtobufException extends IOException {

    public MalformedProtobufException(String message) {
        super(message);
    }

    public MalformedProtobufException(String message, Throwable cause) {
        super(message, cause);
    }

    public static MalformedProtobufException truncated() {
        return new MalformedProtobufException(
                "While parsing a protocol message, the input ended unexpectedly "
                        + "in the middle of a field. This could mean either that the input "
                        + "has been truncated or that an embedded message misreported its own length.");
    }

    public static MalformedProtobufException malformedVarint() {
        return new MalformedProtobufException(
                "CodedInputStream encountered a malformed varint (more than 10 bytes).");
    }

    public static MalformedProtobufException negativeSize() {
        return new MalformedProtobufException(
                "CodedInputStream encountered an embedded string or message which claimed to have negative size.");
    }

    public static MalformedProtobufException invalidWireType(int wireType) {
        return new MalformedProtobufException(
                "Protocol message contained an invalid wire type: " + wireType);
    }

    public static MalformedProtobufException invalidUtf8Encode(Throwable cause) {
        return new MalformedProtobufException(
                "Cannot encode string field as valid UTF-8 (unpaired UTF-16 surrogate, "
                        + "features.utf8_validation = VERIFY).",
                cause);
    }

    public static MalformedProtobufException invalidUtf8(Throwable cause) {
        return new MalformedProtobufException(
                "Protocol message contained a string field with malformed UTF-8 "
                        + "(features.utf8_validation = VERIFY).",
                cause);
    }

    public static MalformedProtobufException recursionLimitExceeded() {
        return new MalformedProtobufException(
                "Protocol message had too many levels of nesting. May be malicious. "
                        + "Use CodedInputStream.setRecursionLimit() to increase the depth limit.");
    }
}
