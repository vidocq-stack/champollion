package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.util.Map;

/**
 * {@code google.protobuf.Struct} — dynamically typed JSON object.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#struct">
 * Struct</a> — equivalent to a JSON object via {@code map<string, Value>}.</p>
 */
@ProtobufMessage("google.protobuf.Struct")
public record Struct(
        @ProtobufField(number = 1, type = FieldType.MAP,
                       mapKey = FieldType.STRING, mapValue = FieldType.MESSAGE)
        Map<String, Value> fields
) implements Message {

    public static final Struct EMPTY = new Struct(Map.of());
}
