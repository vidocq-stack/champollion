package io.vidocq.champollion.protobuf.tck.proto2;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.UnknownFieldSet;
import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Duration;
import io.vidocq.champollion.protobuf.wkt.FieldMask;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.Wrappers;
import java.util.List;
import java.util.Map;

/**
 * <p>Équivalent {@code google/protobuf/test_messages_proto2.proto} pour la
 * conformance Google. Tous les champs singuliers sont {@code explicitPresence=true}
 * (sémantique proto2 {@code optional}).</p>
 *
 * <p>Wire format identique à proto3, sémantique presence différente.
 * Cf. <a href="https://protobuf.dev/programming-guides/proto2/">proto2 spec</a>.</p>
 */
@ProtobufMessage("protobuf_test_messages.proto2.TestAllTypesProto2")
public record TestAllTypesProto2(
        @ProtobufField(number = 1, type = FieldType.INT32, explicitPresence = true) Integer optional_int32,
        @ProtobufField(number = 2, type = FieldType.INT64, explicitPresence = true) Long optional_int64,
        @ProtobufField(number = 3, type = FieldType.UINT32, explicitPresence = true) Integer optional_uint32,
        @ProtobufField(number = 4, type = FieldType.UINT64, explicitPresence = true) Long optional_uint64,
        @ProtobufField(number = 5, type = FieldType.SINT32, explicitPresence = true) Integer optional_sint32,
        @ProtobufField(number = 6, type = FieldType.SINT64, explicitPresence = true) Long optional_sint64,
        @ProtobufField(number = 7, type = FieldType.FIXED32, explicitPresence = true) Integer optional_fixed32,
        @ProtobufField(number = 8, type = FieldType.FIXED64, explicitPresence = true) Long optional_fixed64,
        @ProtobufField(number = 9, type = FieldType.SFIXED32, explicitPresence = true) Integer optional_sfixed32,
        @ProtobufField(number = 10, type = FieldType.SFIXED64, explicitPresence = true) Long optional_sfixed64,
        @ProtobufField(number = 11, type = FieldType.FLOAT, explicitPresence = true) Float optional_float,
        @ProtobufField(number = 12, type = FieldType.DOUBLE, explicitPresence = true) Double optional_double,
        @ProtobufField(number = 13, type = FieldType.BOOL, explicitPresence = true) Boolean optional_bool,
        @ProtobufField(number = 14, type = FieldType.STRING, explicitPresence = true) String optional_string,
        @ProtobufField(number = 15, type = FieldType.BYTES, explicitPresence = true) byte[] optional_bytes,
        @ProtobufField(number = 18, type = FieldType.MESSAGE, explicitPresence = true) NestedMessageP2 optional_nested_message,
        @ProtobufField(number = 21, type = FieldType.ENUM, explicitPresence = true) NestedEnumP2 optional_nested_enum,
        @ProtobufField(number = 31, type = FieldType.INT32) List<Integer> repeated_int32,
        @ProtobufField(number = 32, type = FieldType.INT64) List<Long> repeated_int64,
        @ProtobufField(number = 33, type = FieldType.UINT32) List<Integer> repeated_uint32,
        @ProtobufField(number = 34, type = FieldType.UINT64) List<Long> repeated_uint64,
        @ProtobufField(number = 35, type = FieldType.SINT32) List<Integer> repeated_sint32,
        @ProtobufField(number = 36, type = FieldType.SINT64) List<Long> repeated_sint64,
        @ProtobufField(number = 37, type = FieldType.FIXED32) List<Integer> repeated_fixed32,
        @ProtobufField(number = 38, type = FieldType.FIXED64) List<Long> repeated_fixed64,
        @ProtobufField(number = 39, type = FieldType.SFIXED32) List<Integer> repeated_sfixed32,
        @ProtobufField(number = 40, type = FieldType.SFIXED64) List<Long> repeated_sfixed64,
        @ProtobufField(number = 41, type = FieldType.FLOAT) List<Float> repeated_float,
        @ProtobufField(number = 42, type = FieldType.DOUBLE) List<Double> repeated_double,
        @ProtobufField(number = 43, type = FieldType.BOOL) List<Boolean> repeated_bool,
        @ProtobufField(number = 44, type = FieldType.STRING) List<String> repeated_string,
        @ProtobufField(number = 45, type = FieldType.BYTES) List<byte[]> repeated_bytes,
        @ProtobufField(number = 48, type = FieldType.MESSAGE) List<NestedMessageP2> repeated_nested_message,
        @ProtobufField(number = 51, type = FieldType.ENUM) List<NestedEnumP2> repeated_nested_enum,
        // Oneof (111..120) — explicitPresence=true + oneofGroup pour JSON tracker
        @ProtobufField(number = 111, type = FieldType.UINT32, explicitPresence = true, oneofGroup = "oneof_field") Integer oneof_uint32,
        @ProtobufField(number = 112, type = FieldType.MESSAGE, explicitPresence = true, oneofGroup = "oneof_field") NestedMessageP2 oneof_nested_message,
        @ProtobufField(number = 113, type = FieldType.STRING, explicitPresence = true, oneofGroup = "oneof_field") String oneof_string,
        @ProtobufField(number = 114, type = FieldType.BYTES, explicitPresence = true, oneofGroup = "oneof_field") byte[] oneof_bytes,
        @ProtobufField(number = 115, type = FieldType.BOOL, explicitPresence = true, oneofGroup = "oneof_field") Boolean oneof_bool,
        @ProtobufField(number = 116, type = FieldType.UINT64, explicitPresence = true, oneofGroup = "oneof_field") Long oneof_uint64,
        @ProtobufField(number = 117, type = FieldType.FLOAT, explicitPresence = true, oneofGroup = "oneof_field") Float oneof_float,
        @ProtobufField(number = 118, type = FieldType.DOUBLE, explicitPresence = true, oneofGroup = "oneof_field") Double oneof_double,
        @ProtobufField(number = 119, type = FieldType.ENUM, explicitPresence = true, oneofGroup = "oneof_field") NestedEnumP2 oneof_enum,
        // WKT Wrappers (201..209)
        @ProtobufField(number = 201, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.BoolValue optional_bool_wrapper,
        @ProtobufField(number = 202, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.Int32Value optional_int32_wrapper,
        @ProtobufField(number = 203, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.Int64Value optional_int64_wrapper,
        @ProtobufField(number = 204, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.UInt32Value optional_uint32_wrapper,
        @ProtobufField(number = 205, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.UInt64Value optional_uint64_wrapper,
        @ProtobufField(number = 206, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.FloatValue optional_float_wrapper,
        @ProtobufField(number = 207, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.DoubleValue optional_double_wrapper,
        @ProtobufField(number = 208, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.StringValue optional_string_wrapper,
        @ProtobufField(number = 209, type = FieldType.MESSAGE, explicitPresence = true) Wrappers.BytesValue optional_bytes_wrapper,
        // WKT singletons (301..305)
        @ProtobufField(number = 301, type = FieldType.MESSAGE, explicitPresence = true) Duration optional_duration,
        @ProtobufField(number = 302, type = FieldType.MESSAGE, explicitPresence = true) Timestamp optional_timestamp,
        @ProtobufField(number = 303, type = FieldType.MESSAGE, explicitPresence = true) FieldMask optional_field_mask,
        @ProtobufField(number = 304, type = FieldType.MESSAGE, explicitPresence = true) io.vidocq.champollion.protobuf.wkt.Struct optional_struct,
        @ProtobufField(number = 305, type = FieldType.MESSAGE, explicitPresence = true) Any optional_any,
        @ProtobufField(number = 306, type = FieldType.MESSAGE, explicitPresence = true) io.vidocq.champollion.protobuf.wkt.Value optional_value,
        @ProtobufField(number = 307, type = FieldType.ENUM, explicitPresence = true) io.vidocq.champollion.protobuf.wkt.NullValue optional_null_value,
        // Maps (numéros 56..74, conforme Google test_messages_proto2.proto)
        @ProtobufField(number = 56, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.INT32) Map<Integer, Integer> map_int32_int32,
        @ProtobufField(number = 57, type = FieldType.MAP, mapKey = FieldType.INT64, mapValue = FieldType.INT64) Map<Long, Long> map_int64_int64,
        @ProtobufField(number = 58, type = FieldType.MAP, mapKey = FieldType.UINT32, mapValue = FieldType.UINT32) Map<Integer, Integer> map_uint32_uint32,
        @ProtobufField(number = 59, type = FieldType.MAP, mapKey = FieldType.UINT64, mapValue = FieldType.UINT64) Map<Long, Long> map_uint64_uint64,
        @ProtobufField(number = 60, type = FieldType.MAP, mapKey = FieldType.SINT32, mapValue = FieldType.SINT32) Map<Integer, Integer> map_sint32_sint32,
        @ProtobufField(number = 61, type = FieldType.MAP, mapKey = FieldType.SINT64, mapValue = FieldType.SINT64) Map<Long, Long> map_sint64_sint64,
        @ProtobufField(number = 62, type = FieldType.MAP, mapKey = FieldType.FIXED32, mapValue = FieldType.FIXED32) Map<Integer, Integer> map_fixed32_fixed32,
        @ProtobufField(number = 63, type = FieldType.MAP, mapKey = FieldType.FIXED64, mapValue = FieldType.FIXED64) Map<Long, Long> map_fixed64_fixed64,
        @ProtobufField(number = 64, type = FieldType.MAP, mapKey = FieldType.SFIXED32, mapValue = FieldType.SFIXED32) Map<Integer, Integer> map_sfixed32_sfixed32,
        @ProtobufField(number = 65, type = FieldType.MAP, mapKey = FieldType.SFIXED64, mapValue = FieldType.SFIXED64) Map<Long, Long> map_sfixed64_sfixed64,
        @ProtobufField(number = 66, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.FLOAT) Map<Integer, Float> map_int32_float,
        @ProtobufField(number = 67, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.DOUBLE) Map<Integer, Double> map_int32_double,
        @ProtobufField(number = 68, type = FieldType.MAP, mapKey = FieldType.BOOL, mapValue = FieldType.BOOL) Map<Boolean, Boolean> map_bool_bool,
        @ProtobufField(number = 69, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.STRING) Map<String, String> map_string_string,
        @ProtobufField(number = 70, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.BYTES) Map<String, byte[]> map_string_bytes,
        @ProtobufField(number = 71, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.MESSAGE) Map<String, NestedMessageP2> map_string_nested_message,
        @ProtobufField(number = 73, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.ENUM) Map<String, NestedEnumP2> map_string_nested_enum,
        // Packed/unpacked repeated scalars (proto2 §packed_option).
        @ProtobufField(number = 75, type = FieldType.INT32, packed = true) List<Integer> packed_int32,
        @ProtobufField(number = 89, type = FieldType.INT32, packed = false) List<Integer> unpacked_int32,
        // Field-name-to-JSON-name convention (401..418)
        @ProtobufField(number = 401, type = FieldType.INT32, explicitPresence = true) Integer fieldname1,
        @ProtobufField(number = 402, type = FieldType.INT32, explicitPresence = true) Integer field_name2,
        @ProtobufField(number = 403, type = FieldType.INT32, explicitPresence = true) Integer _field_name3,
        @ProtobufField(number = 404, type = FieldType.INT32, explicitPresence = true) Integer field__name4_,
        @ProtobufField(number = 405, type = FieldType.INT32, explicitPresence = true) Integer field0name5,
        @ProtobufField(number = 406, type = FieldType.INT32, explicitPresence = true) Integer field_0_name6,
        @ProtobufField(number = 407, type = FieldType.INT32, explicitPresence = true) Integer fieldName7,
        @ProtobufField(number = 408, type = FieldType.INT32, explicitPresence = true) Integer FieldName8,
        @ProtobufField(number = 409, type = FieldType.INT32, explicitPresence = true) Integer field_Name9,
        @ProtobufField(number = 410, type = FieldType.INT32, explicitPresence = true) Integer Field_Name10,
        @ProtobufField(number = 411, type = FieldType.INT32, explicitPresence = true) Integer FIELD_NAME11,
        @ProtobufField(number = 412, type = FieldType.INT32, explicitPresence = true) Integer FIELD_name12,
        @ProtobufField(number = 413, type = FieldType.INT32, explicitPresence = true) Integer __field_name13,
        @ProtobufField(number = 414, type = FieldType.INT32, explicitPresence = true) Integer __Field_name14,
        @ProtobufField(number = 415, type = FieldType.INT32, explicitPresence = true) Integer field__name15,
        @ProtobufField(number = 416, type = FieldType.INT32, explicitPresence = true) Integer field__Name16,
        @ProtobufField(number = 417, type = FieldType.INT32, explicitPresence = true) Integer field_name17__,
        @ProtobufField(number = 418, type = FieldType.INT32, explicitPresence = true) Integer Field_name18__,
        UnknownFieldSet unknownFields
) implements Message {}
