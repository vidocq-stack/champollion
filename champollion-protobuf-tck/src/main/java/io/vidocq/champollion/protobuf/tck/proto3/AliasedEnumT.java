package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

/**
 * {@code TestAllTypesProto3.AliasedEnum} — enum avec {@code option allow_alias = true}.
 *
 * <p>Plusieurs constantes Java pointent vers la même valeur proto via
 * {@link ProtoEnumValue} :</p>
 *
 * <pre>
 * ALIAS_FOO = 0
 * ALIAS_BAR = 1
 * ALIAS_BAZ = 2
 * MOO      = 2   (alias de ALIAS_BAZ)
 * moo      = 2   (alias casse-différente)
 * bAz      = 2   (alias casse-différente bis)
 * </pre>
 *
 * <p>Lors de la désérialisation, la première constante déclarée avec
 * {@code value=2} (donc {@code ALIAS_BAZ}) est utilisée — c'est l'ordre
 * de déclaration qui gagne en cas d'aliasing.</p>
 */
public enum AliasedEnumT {
    ALIAS_FOO,                       // 0
    ALIAS_BAR,                       // 1
    ALIAS_BAZ,                       // 2
    @ProtoEnumValue(2) MOO,          // alias 2
    @ProtoEnumValue(2) moo,          // alias 2
    @ProtoEnumValue(2) bAz           // alias 2
}
