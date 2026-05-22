package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;
import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Duration;
import io.vidocq.champollion.protobuf.wkt.FieldMask;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.Wrappers;
import java.util.List;

/**
 * <p><b>M5.5.4 — étendu manuellement.</b> Les champs scalaires 1..15 et
 * repeated 31..34 sont issus de {@code generate-tck-sources.sh}. Les champs
 * WKT 17..29 ont été ajoutés à la main car notre {@code SchemaResolver}
 * ne sait pas encore importer {@code google/protobuf/duration.proto} et
 * mapper {@code google.protobuf.Duration → io.vidocq.champollion.protobuf.wkt.Duration}.
 * À industrialiser en M5.6 (option {@code --external-types} du CLI codegen).</p>
 */
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
        @ProtobufField(number = 34, type = FieldType.BYTES) List<byte[]> repeated_bytes,
        // --- WKT Wrappers (numéros 201..209, conforme Google) ---
        @ProtobufField(number = 201, type = FieldType.MESSAGE) Wrappers.BoolValue optional_bool_wrapper,
        @ProtobufField(number = 202, type = FieldType.MESSAGE) Wrappers.Int32Value optional_int32_wrapper,
        @ProtobufField(number = 203, type = FieldType.MESSAGE) Wrappers.Int64Value optional_int64_wrapper,
        @ProtobufField(number = 204, type = FieldType.MESSAGE) Wrappers.UInt32Value optional_uint32_wrapper,
        @ProtobufField(number = 205, type = FieldType.MESSAGE) Wrappers.UInt64Value optional_uint64_wrapper,
        @ProtobufField(number = 206, type = FieldType.MESSAGE) Wrappers.FloatValue optional_float_wrapper,
        @ProtobufField(number = 207, type = FieldType.MESSAGE) Wrappers.DoubleValue optional_double_wrapper,
        @ProtobufField(number = 208, type = FieldType.MESSAGE) Wrappers.StringValue optional_string_wrapper,
        @ProtobufField(number = 209, type = FieldType.MESSAGE) Wrappers.BytesValue optional_bytes_wrapper,
        // --- WKT singletons (numéros 301..305, conforme Google) ---
        @ProtobufField(number = 301, type = FieldType.MESSAGE) Duration optional_duration,
        @ProtobufField(number = 302, type = FieldType.MESSAGE) Timestamp optional_timestamp,
        @ProtobufField(number = 303, type = FieldType.MESSAGE) FieldMask optional_field_mask,
        @ProtobufField(number = 305, type = FieldType.MESSAGE) Any optional_any
) implements Message {}
