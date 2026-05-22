package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

/**
 * {@code TestAllTypesProto3.NestedEnum} — proto3, NEG=-1 inclus.
 *
 * <p>Top-level pour éviter la collision package/classe générée par
 * l'APT {@code ProtobufStaticProcessor} sur les types imbriqués.</p>
 */
public enum NestedEnumT {
    FOO,                          // proto value = 0 (default)
    BAR,                          // proto value = 1
    BAZ,                          // proto value = 2
    @ProtoEnumValue(-1) NEG       // proto value = -1
}
