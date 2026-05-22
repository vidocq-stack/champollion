package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mappe une constante d'enum Java vers son entier proto correspondant.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#enum">
 * Proto3 §Enumerations</a>. Les valeurs proto sont des {@code int32} signés
 * ; par défaut Champollion utilise {@code ordinal()} (0, 1, 2, ...), mais
 * cette annotation permet d'override :</p>
 *
 * <ul>
 *   <li><b>Valeurs négatives</b> (ex. proto {@code NEG = -1;}) que
 *       {@code ordinal()} ne peut pas représenter.</li>
 *   <li><b>Enum aliasing</b> ({@code option allow_alias = true;}) où plusieurs
 *       constantes Java pointent vers la même valeur proto. Lors de la
 *       désérialisation, le runtime retourne la première constante déclarée
 *       avec cette valeur.</li>
 * </ul>
 *
 * <p>Usage :</p>
 * <pre>{@code
 * public enum NestedEnum {
 *     FOO,                         // value = 0 (ordinal par défaut)
 *     BAR,                         // value = 1
 *     BAZ,                         // value = 2
 *     @ProtoEnumValue(-1) NEG      // value = -1 (override)
 * }
 *
 * public enum AliasedEnum {
 *     ALIAS_FOO,                          // value = 0
 *     ALIAS_BAR,                          // value = 1
 *     ALIAS_BAZ,                          // value = 2
 *     @ProtoEnumValue(2) MOO,             // alias de ALIAS_BAZ
 *     @ProtoEnumValue(2) moo,             // alias casse-différente
 * }
 * }</pre>
 *
 * <p>Si l'annotation est absente, la valeur proto utilisée est
 * {@code constant.ordinal()} (rétrocompatible avec les enums déjà déclarés).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD) // les constants d'enum sont des fields statiques
public @interface ProtoEnumValue {
    int value();
}
