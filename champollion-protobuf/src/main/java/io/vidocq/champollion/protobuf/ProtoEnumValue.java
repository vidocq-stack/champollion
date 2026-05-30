package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a Java enum constant to its corresponding proto integer.
 *
 * <p>Spec: <a href="https://protobuf.dev/programming-guides/proto3/#enum">
 * Proto3 §Enumerations</a>. Proto values are signed {@code int32}s;
 * by default Champollion uses {@code ordinal()} (0, 1, 2, ...), but
 * this annotation lets you override it:</p>
 *
 * <ul>
 *   <li><b>Negative values</b> (e.g. proto {@code NEG = -1;}) that
 *       {@code ordinal()} cannot represent.</li>
 *   <li><b>Enum aliasing</b> ({@code option allow_alias = true;}) where multiple
 *       Java constants map to the same proto value. During deserialization,
 *       the runtime returns the first declared constant for that value.</li>
 * </ul>
 *
 * <p>Usage :</p>
 * <pre>{@code
 * public enum NestedEnum {
 *     FOO,                         // value = 0 (default ordinal)
 *     BAR,                         // value = 1
 *     BAZ,                         // value = 2
 *     @ProtoEnumValue(-1) NEG      // value = -1 (override)
 * }
 *
 * public enum AliasedEnum {
 *     ALIAS_FOO,                          // value = 0
 *     ALIAS_BAR,                          // value = 1
 *     ALIAS_BAZ,                          // value = 2
 *     @ProtoEnumValue(2) MOO,             // alias of ALIAS_BAZ
 *     @ProtoEnumValue(2) moo,             // different-cased alias
 * }
 * }</pre>
 *
 * <p>If the annotation is absent, the proto value used is
 * {@code constant.ordinal()} (backward-compatible with enums already declared).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD) // enum constants are static fields
public @interface ProtoEnumValue {
    int value();
}
