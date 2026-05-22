package io.vidocq.champollion.protobuf.tck.proto2;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code TestAllTypesProto2.NestedMessage} — sub-message récursif (proto2).
 *
 * <p>{@code optional int32 a = 1; optional TestAllTypesProto2 corecursive = 2;}.</p>
 */
@ProtobufMessage("protobuf_test_messages.proto2.TestAllTypesProto2.NestedMessage")
public record NestedMessageP2(
        @ProtobufField(number = 1, type = FieldType.INT32, explicitPresence = true) Integer a,
        @ProtobufField(number = 2, type = FieldType.MESSAGE, explicitPresence = true) TestAllTypesProto2 corecursive
) implements Message {}
