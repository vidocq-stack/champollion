package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;
import java.util.List;

@ProtobufStatic
@ProtobufMessage("protobuf_test_messages.proto3.TestAllTypesProto3")
public record TestAllTypesProto3(
        @ProtobufField(number = 1, type = FieldType.INT32) int optional_int32,
        @ProtobufField(number = 2, type = FieldType.INT64) long optional_int64,
        @ProtobufField(number = 3, type = FieldType.UINT32) int optional_uint32,
        @ProtobufField(number = 4, type = FieldType.UINT64) long optional_uint64,
        @ProtobufField(number = 5, type = FieldType.SINT32) int optional_sint32,
        @ProtobufField(number = 6, type = FieldType.SINT64) long optional_sint64,
        @ProtobufField(number = 7, type = FieldType.FIXED32) int optional_fixed32,
        @ProtobufField(number = 8, type = FieldType.FIXED64) long optional_fixed64,
        @ProtobufField(number = 9, type = FieldType.SFIXED32) int optional_sfixed32,
        @ProtobufField(number = 10, type = FieldType.SFIXED64) long optional_sfixed64,
        @ProtobufField(number = 11, type = FieldType.FLOAT) float optional_float,
        @ProtobufField(number = 12, type = FieldType.DOUBLE) double optional_double,
        @ProtobufField(number = 13, type = FieldType.BOOL) boolean optional_bool,
        @ProtobufField(number = 14, type = FieldType.STRING) String optional_string,
        @ProtobufField(number = 15, type = FieldType.BYTES) byte[] optional_bytes,
        @ProtobufField(number = 31, type = FieldType.INT32) List<Integer> repeated_int32,
        @ProtobufField(number = 32, type = FieldType.INT64) List<Long> repeated_int64,
        @ProtobufField(number = 33, type = FieldType.STRING) List<String> repeated_string,
        @ProtobufField(number = 34, type = FieldType.BYTES) List<byte[]> repeated_bytes
) implements Message {}
