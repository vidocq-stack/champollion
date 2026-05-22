package io.vidocq.champollion.protobuf.tck.proto3;

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
 * <p><b>M5.5.5 — étendu manuellement.</b> Modèle quasi-complet de
 * {@code google/protobuf/test_messages_proto3.proto} pour la conformance
 * Google. Notre {@code SchemaResolver} ne sait pas encore importer
 * {@code google/protobuf/*.proto} cross-fichier ; les champs WKT et les
 * messages/enums nested sont écrits à la main. À industrialiser en M5.6.</p>
 *
 * <p>{@link NestedMessageT} et {@link NestedEnumT} sont sortis en top-level
 * pour contourner une limite de l'APT {@code ProtobufStaticProcessor} qui
 * matérialise les nested type names en sous-packages (collision).</p>
 */
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
        @ProtobufField(number = 18, type = FieldType.MESSAGE) NestedMessageT optional_nested_message,
        @ProtobufField(number = 21, type = FieldType.ENUM) NestedEnumT optional_nested_enum,
        @ProtobufField(number = 23, type = FieldType.ENUM) AliasedEnumT optional_aliased_enum,
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
        @ProtobufField(number = 48, type = FieldType.MESSAGE) List<NestedMessageT> repeated_nested_message,
        @ProtobufField(number = 51, type = FieldType.ENUM) List<NestedEnumT> repeated_nested_enum,
        @ProtobufField(number = 75, type = FieldType.INT32) List<Integer> packed_int32,
        @ProtobufField(number = 76, type = FieldType.INT64) List<Long> packed_int64,
        @ProtobufField(number = 77, type = FieldType.UINT32) List<Integer> packed_uint32,
        @ProtobufField(number = 78, type = FieldType.UINT64) List<Long> packed_uint64,
        @ProtobufField(number = 79, type = FieldType.SINT32) List<Integer> packed_sint32,
        @ProtobufField(number = 80, type = FieldType.SINT64) List<Long> packed_sint64,
        @ProtobufField(number = 81, type = FieldType.FIXED32) List<Integer> packed_fixed32,
        @ProtobufField(number = 82, type = FieldType.FIXED64) List<Long> packed_fixed64,
        @ProtobufField(number = 83, type = FieldType.SFIXED32) List<Integer> packed_sfixed32,
        @ProtobufField(number = 84, type = FieldType.SFIXED64) List<Long> packed_sfixed64,
        @ProtobufField(number = 85, type = FieldType.FLOAT) List<Float> packed_float,
        @ProtobufField(number = 86, type = FieldType.DOUBLE) List<Double> packed_double,
        @ProtobufField(number = 87, type = FieldType.BOOL) List<Boolean> packed_bool,
        @ProtobufField(number = 88, type = FieldType.ENUM) List<NestedEnumT> packed_nested_enum,
        @ProtobufField(number = 89, type = FieldType.INT32, packed = false) List<Integer> unpacked_int32,
        @ProtobufField(number = 90, type = FieldType.INT64, packed = false) List<Long> unpacked_int64,
        @ProtobufField(number = 91, type = FieldType.UINT32, packed = false) List<Integer> unpacked_uint32,
        @ProtobufField(number = 92, type = FieldType.UINT64, packed = false) List<Long> unpacked_uint64,
        @ProtobufField(number = 93, type = FieldType.SINT32, packed = false) List<Integer> unpacked_sint32,
        @ProtobufField(number = 94, type = FieldType.SINT64, packed = false) List<Long> unpacked_sint64,
        @ProtobufField(number = 95, type = FieldType.FIXED32, packed = false) List<Integer> unpacked_fixed32,
        @ProtobufField(number = 96, type = FieldType.FIXED64, packed = false) List<Long> unpacked_fixed64,
        @ProtobufField(number = 97, type = FieldType.SFIXED32, packed = false) List<Integer> unpacked_sfixed32,
        @ProtobufField(number = 98, type = FieldType.SFIXED64, packed = false) List<Long> unpacked_sfixed64,
        @ProtobufField(number = 99, type = FieldType.FLOAT, packed = false) List<Float> unpacked_float,
        @ProtobufField(number = 100, type = FieldType.DOUBLE, packed = false) List<Double> unpacked_double,
        @ProtobufField(number = 101, type = FieldType.BOOL, packed = false) List<Boolean> unpacked_bool,
        @ProtobufField(number = 102, type = FieldType.ENUM, packed = false) List<NestedEnumT> unpacked_nested_enum,
        @ProtobufField(number = 111, type = FieldType.UINT32, explicitPresence = true, oneofGroup = "oneof_field") Integer oneof_uint32,
        @ProtobufField(number = 112, type = FieldType.MESSAGE, explicitPresence = true, oneofGroup = "oneof_field") NestedMessageT oneof_nested_message,
        @ProtobufField(number = 113, type = FieldType.STRING, explicitPresence = true, oneofGroup = "oneof_field") String oneof_string,
        @ProtobufField(number = 114, type = FieldType.BYTES, explicitPresence = true, oneofGroup = "oneof_field") byte[] oneof_bytes,
        @ProtobufField(number = 115, type = FieldType.BOOL, explicitPresence = true, oneofGroup = "oneof_field") Boolean oneof_bool,
        @ProtobufField(number = 116, type = FieldType.UINT64, explicitPresence = true, oneofGroup = "oneof_field") Long oneof_uint64,
        @ProtobufField(number = 117, type = FieldType.FLOAT, explicitPresence = true, oneofGroup = "oneof_field") Float oneof_float,
        @ProtobufField(number = 118, type = FieldType.DOUBLE, explicitPresence = true, oneofGroup = "oneof_field") Double oneof_double,
        @ProtobufField(number = 119, type = FieldType.ENUM, explicitPresence = true, oneofGroup = "oneof_field") NestedEnumT oneof_enum,
        @ProtobufField(number = 201, type = FieldType.MESSAGE) Wrappers.BoolValue optional_bool_wrapper,
        @ProtobufField(number = 202, type = FieldType.MESSAGE) Wrappers.Int32Value optional_int32_wrapper,
        @ProtobufField(number = 203, type = FieldType.MESSAGE) Wrappers.Int64Value optional_int64_wrapper,
        @ProtobufField(number = 204, type = FieldType.MESSAGE) Wrappers.UInt32Value optional_uint32_wrapper,
        @ProtobufField(number = 205, type = FieldType.MESSAGE) Wrappers.UInt64Value optional_uint64_wrapper,
        @ProtobufField(number = 206, type = FieldType.MESSAGE) Wrappers.FloatValue optional_float_wrapper,
        @ProtobufField(number = 207, type = FieldType.MESSAGE) Wrappers.DoubleValue optional_double_wrapper,
        @ProtobufField(number = 208, type = FieldType.MESSAGE) Wrappers.StringValue optional_string_wrapper,
        @ProtobufField(number = 209, type = FieldType.MESSAGE) Wrappers.BytesValue optional_bytes_wrapper,
        @ProtobufField(number = 211, type = FieldType.MESSAGE) List<Wrappers.BoolValue> repeated_bool_wrapper,
        @ProtobufField(number = 212, type = FieldType.MESSAGE) List<Wrappers.Int32Value> repeated_int32_wrapper,
        @ProtobufField(number = 213, type = FieldType.MESSAGE) List<Wrappers.Int64Value> repeated_int64_wrapper,
        @ProtobufField(number = 214, type = FieldType.MESSAGE) List<Wrappers.UInt32Value> repeated_uint32_wrapper,
        @ProtobufField(number = 215, type = FieldType.MESSAGE) List<Wrappers.UInt64Value> repeated_uint64_wrapper,
        @ProtobufField(number = 216, type = FieldType.MESSAGE) List<Wrappers.FloatValue> repeated_float_wrapper,
        @ProtobufField(number = 217, type = FieldType.MESSAGE) List<Wrappers.DoubleValue> repeated_double_wrapper,
        @ProtobufField(number = 218, type = FieldType.MESSAGE) List<Wrappers.StringValue> repeated_string_wrapper,
        @ProtobufField(number = 219, type = FieldType.MESSAGE) List<Wrappers.BytesValue> repeated_bytes_wrapper,
        @ProtobufField(number = 301, type = FieldType.MESSAGE) Duration optional_duration,
        @ProtobufField(number = 302, type = FieldType.MESSAGE) Timestamp optional_timestamp,
        @ProtobufField(number = 303, type = FieldType.MESSAGE) FieldMask optional_field_mask,
        @ProtobufField(number = 304, type = FieldType.MESSAGE) io.vidocq.champollion.protobuf.wkt.Struct optional_struct,
        @ProtobufField(number = 305, type = FieldType.MESSAGE) Any optional_any,
        @ProtobufField(number = 306, type = FieldType.MESSAGE) io.vidocq.champollion.protobuf.wkt.Value optional_value,
        @ProtobufField(number = 307, type = FieldType.ENUM) io.vidocq.champollion.protobuf.wkt.NullValue optional_null_value,
        @ProtobufField(number = 311, type = FieldType.MESSAGE) List<Duration> repeated_duration,
        @ProtobufField(number = 312, type = FieldType.MESSAGE) List<Timestamp> repeated_timestamp,
        @ProtobufField(number = 313, type = FieldType.MESSAGE) List<FieldMask> repeated_fieldmask,
        @ProtobufField(number = 315, type = FieldType.MESSAGE) List<Any> repeated_any,
        @ProtobufField(number = 316, type = FieldType.MESSAGE) List<io.vidocq.champollion.protobuf.wkt.Value> repeated_value,
        @ProtobufField(number = 317, type = FieldType.MESSAGE) List<io.vidocq.champollion.protobuf.wkt.ListValue> repeated_list_value,
        // Maps (numéros 56..74, conforme Google test_messages_proto3.proto §maps).
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
        @ProtobufField(number = 71, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.MESSAGE) Map<String, NestedMessageT> map_string_nested_message,
        @ProtobufField(number = 73, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.ENUM) Map<String, NestedEnumT> map_string_nested_enum,
        // Field-name-to-JSON-name convention (401..418)
        @ProtobufField(number = 401, type = FieldType.INT32) int fieldname1,
        @ProtobufField(number = 402, type = FieldType.INT32) int field_name2,
        @ProtobufField(number = 403, type = FieldType.INT32) int _field_name3,
        @ProtobufField(number = 404, type = FieldType.INT32) int field__name4_,
        @ProtobufField(number = 405, type = FieldType.INT32) int field0name5,
        @ProtobufField(number = 406, type = FieldType.INT32) int field_0_name6,
        @ProtobufField(number = 407, type = FieldType.INT32) int fieldName7,
        @ProtobufField(number = 408, type = FieldType.INT32) int FieldName8,
        @ProtobufField(number = 409, type = FieldType.INT32) int field_Name9,
        @ProtobufField(number = 410, type = FieldType.INT32) int Field_Name10,
        @ProtobufField(number = 411, type = FieldType.INT32) int FIELD_NAME11,
        @ProtobufField(number = 412, type = FieldType.INT32) int FIELD_name12,
        @ProtobufField(number = 413, type = FieldType.INT32) int __field_name13,
        @ProtobufField(number = 414, type = FieldType.INT32) int __Field_name14,
        @ProtobufField(number = 415, type = FieldType.INT32) int field__name15,
        @ProtobufField(number = 416, type = FieldType.INT32) int field__Name16,
        @ProtobufField(number = 417, type = FieldType.INT32) int field_name17__,
        @ProtobufField(number = 418, type = FieldType.INT32) int Field_name18__,
        // Composant spécial : collecte les fields inconnus pour ré-émission (forward-compat).
        // Détecté par RuntimeBinding via le type UnknownFieldSet + le nom 'unknownFields'.
        UnknownFieldSet unknownFields
) implements Message {}
