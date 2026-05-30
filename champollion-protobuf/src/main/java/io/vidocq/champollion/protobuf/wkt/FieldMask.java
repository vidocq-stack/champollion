package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.util.List;

/**
 * {@code google.protobuf.FieldMask} — projection over a subset of fields.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#field-mask">FieldMask</a>.
 * Canonical JSON: a single comma-separated string with paths converted
 * to {@code lowerCamelCase}, e.g. {@code "user.firstName,user.address.zipCode"}.</p>
 */
@ProtobufMessage("google.protobuf.FieldMask")
public record FieldMask(
        @ProtobufField(number = 1, type = FieldType.STRING) List<String> paths
) implements Message {

    public FieldMask {
        paths = List.copyOf(paths);
    }

    public static FieldMask of(String... paths) {
        return new FieldMask(List.of(paths));
    }
}
