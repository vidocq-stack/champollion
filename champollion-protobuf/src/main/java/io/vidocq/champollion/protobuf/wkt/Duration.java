package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code google.protobuf.Duration} — signed duration.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#duration">Duration</a>.
 * Canonical JSON: {@code "3.5s"} (optional fractional seconds, mandatory
 * {@code s} suffix). Both components must have the same sign.</p>
 */
@ProtobufMessage("google.protobuf.Duration")
public record Duration(
        @ProtobufField(number = 1, type = FieldType.INT64) long seconds,
        @ProtobufField(number = 2, type = FieldType.INT32) int nanos
) implements Message {

    public static Duration ofSeconds(long seconds) {
        return new Duration(seconds, 0);
    }

    public static Duration of(long seconds, int nanos) {
        return new Duration(seconds, nanos);
    }

    public static Duration from(java.time.Duration jd) {
        return new Duration(jd.getSeconds(), jd.getNano());
    }

    public java.time.Duration toJavaDuration() {
        return java.time.Duration.ofSeconds(seconds, nanos);
    }
}
