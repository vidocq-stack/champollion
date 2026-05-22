package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;

/**
 * {@code TestAllTypesProto3.NestedMessage} — sub-message récursif.
 *
 * <p>Top-level pour éviter la collision package/classe générée par
 * {@code ProtobufStaticProcessor} sur les types imbriqués (M5.5.5 note).</p>
 */
@ProtobufStatic
@ProtobufMessage("protobuf_test_messages.proto3.TestAllTypesProto3.NestedMessage")
public record NestedMessageT(
        @ProtobufField(number = 1, type = FieldType.INT32) int a,
        @ProtobufField(number = 2, type = FieldType.MESSAGE) TestAllTypesProto3 corecursive
) implements Message {}
