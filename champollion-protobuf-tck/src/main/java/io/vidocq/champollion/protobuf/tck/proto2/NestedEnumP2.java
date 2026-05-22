package io.vidocq.champollion.protobuf.tck.proto2;

/**
 * {@code TestAllTypesProto2.NestedEnum} — FOO/BAR/BAZ/NEG=-1.
 *
 * <p>Identique à {@code proto3.NestedEnumT} sémantiquement, dupliqué pour
 * éviter la collision package APT et clarifier le fullName proto2.</p>
 */
public enum NestedEnumP2 {
    FOO,
    BAR,
    BAZ,
    @io.vidocq.champollion.protobuf.ProtoEnumValue(-1) NEG
}
