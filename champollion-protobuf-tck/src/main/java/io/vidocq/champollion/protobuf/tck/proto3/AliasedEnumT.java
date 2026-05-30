package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

/**
 * {@code TestAllTypesProto3.AliasedEnum} — enum avec {@code option allow_alias = true}.
 *
 * <p>Multiple Java constants point to the same proto value via
 * {@link ProtoEnumValue} :</p>
 *
 * <pre>
 * ALIAS_FOO = 0
 * ALIAS_BAR = 1
 * ALIAS_BAZ = 2
 * MOO      = 2   (alias of ALIAS_BAZ)
 * moo      = 2   (alias different-case)
 * bAz      = 2   (alias different-case bis)
 * </pre>
 *
 * <p>During deserialization, the first constant declared with
 * {@code value=2} (i.e. {@code ALIAS_BAZ}) is used — declaration order
 * wins in case of aliasing.</p>
 */
public enum AliasedEnumT {
    ALIAS_FOO,                       // 0
    ALIAS_BAR,                       // 1
    ALIAS_BAZ,                       // 2
    @ProtoEnumValue(2) MOO,          // alias 2
    @ProtoEnumValue(2) moo,          // alias 2
    @ProtoEnumValue(2) bAz           // alias 2
}
