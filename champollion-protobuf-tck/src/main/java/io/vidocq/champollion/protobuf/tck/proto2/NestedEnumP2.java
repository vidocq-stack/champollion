package io.vidocq.champollion.protobuf.tck.proto2;

/**
 * {@code TestAllTypesProto2.NestedEnum} — FOO/BAR/BAZ/NEG=-1.
 *
 * <p>Identical to {@code proto3.NestedEnumT} semantically, duplicated to
 * avoid APT package collision and clarify the proto2 fullName.</p>
 */
public enum NestedEnumP2 {
    FOO,
    BAR,
    BAZ,
    @io.vidocq.champollion.protobuf.ProtoEnumValue(-1) NEG
}
