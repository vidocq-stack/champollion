package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code google.protobuf.Duration} — durée signée.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#duration">Duration</a>.
 * JSON canonical : {@code "3.5s"} (fraction de seconde optionnelle, suffixe
 * obligatoire {@code s}). Les deux composants doivent avoir le même signe.</p>
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
