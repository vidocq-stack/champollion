package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * Wrappers {@code google.protobuf.*Value} — version « boxée » des scalaires
 * permettant l'expression de la présence explicite en proto3.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#wrapper-types">Wrapper types</a>.
 * JSON canonical : la <b>valeur primitive directement</b>, sans wrapper d'objet
 * (ex. {@code 42} et pas {@code {"value":42}}).</p>
 */
public final class Wrappers {

    private Wrappers() {}

    @ProtobufMessage("google.protobuf.DoubleValue")
    public record DoubleValue(@ProtobufField(number = 1, type = FieldType.DOUBLE) double value)
            implements Message {}

    @ProtobufMessage("google.protobuf.FloatValue")
    public record FloatValue(@ProtobufField(number = 1, type = FieldType.FLOAT) float value)
            implements Message {}

    @ProtobufMessage("google.protobuf.Int64Value")
    public record Int64Value(@ProtobufField(number = 1, type = FieldType.INT64) long value)
            implements Message {}

    @ProtobufMessage("google.protobuf.UInt64Value")
    public record UInt64Value(@ProtobufField(number = 1, type = FieldType.UINT64) long value)
            implements Message {}

    @ProtobufMessage("google.protobuf.Int32Value")
    public record Int32Value(@ProtobufField(number = 1, type = FieldType.INT32) int value)
            implements Message {}

    @ProtobufMessage("google.protobuf.UInt32Value")
    public record UInt32Value(@ProtobufField(number = 1, type = FieldType.UINT32) int value)
            implements Message {}

    @ProtobufMessage("google.protobuf.BoolValue")
    public record BoolValue(@ProtobufField(number = 1, type = FieldType.BOOL) boolean value)
            implements Message {}

    @ProtobufMessage("google.protobuf.StringValue")
    public record StringValue(@ProtobufField(number = 1, type = FieldType.STRING) String value)
            implements Message {}

    @ProtobufMessage("google.protobuf.BytesValue")
    public record BytesValue(@ProtobufField(number = 1, type = FieldType.BYTES) byte[] value)
            implements Message {}
}
