package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.util.List;

/**
 * {@code google.protobuf.ListValue} — dynamically typed JSON array.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#list-value">
 * ListValue</a> — equivalent to a JSON array via {@code repeated Value}.</p>
 */
@ProtobufMessage("google.protobuf.ListValue")
public record ListValue(
        @ProtobufField(number = 1, type = FieldType.MESSAGE)
        List<Value> values
) implements Message {

    public static final ListValue EMPTY = new ListValue(List.of());
}
