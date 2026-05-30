package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata for a record component participating in a Protocol Buffers message.
 *
 * <p>{@link #number()} is the wire format {@code field_number}
 * (1..2^29-1, see {@link WireFormat#MAX_FIELD_NUMBER}). {@link #type()} removes
 * ambiguity between possible encodings for the same Java type (e.g. {@code int}
 * can be INT32, UINT32, SINT32, FIXED32 or SFIXED32).</p>
 *
 * <p>For {@code repeated} fields (Java {@code List<X>}), {@link #packed()}
 * controls the encoding (packed by default in proto3 for packable scalar types,
 * see {@link FieldType#packable()}).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface ProtobufField {

    int number();

    FieldType type();

    /**
     * Encoding for {@code repeated <scalar>}. Ignored for non-repeated
     * or non-packable fields. {@code true} by default (proto3 / Editions 2023).
     */
    boolean packed() default true;

    /**
     * {@code features.field_presence = EXPLICIT} (proto2 / Edition 2023 override) —
     * serializes the field even if its value equals the proto3 default (empty string,
     * 0, false). {@code false} by default (proto3 IMPLICIT: omit defaults).
     *
     * <p>{@code null} reste toujours omis : ce flag distingue {@code default} de
     * {@code absent}, pas l'inverse.</p>
     */
    boolean explicitPresence() default false;

    /**
     * Name of the {@code oneof} group if this field belongs to one. Several fields
     * with the same non-empty value form a oneof. Empty by default (not in
     * a oneof).
     *
     * <p>Effect: the JSON parser ({@code ProtobufJson.fromJson}) explicitly rejects
     * two property keys from the same group (proto3 JSON §oneof spec).
     * The wire format is not affected — it already allows several fields from a
     * oneof to travel together, last-wins (proto3 §oneof spec).</p>
     *
     * <p>JSON {@code null} values are excluded from the tracker (consistent with
     * "null = absent" in canonical proto3 JSON).</p>
     */
    String oneofGroup() default "";

    /**
     * Key type for {@link FieldType#MAP}. Ignored otherwise.
     * <p>Removes ambiguity for numeric Java types (Integer →
     * INT32/UINT32/SINT32/FIXED32/SFIXED32). See proto3 §maps spec —
     * {@code map<K,V>} : K ∈ {int32, int64, uint32, uint64, sint32, sint64,
     * fixed32, fixed64, sfixed32, sfixed64, bool, string}.</p>
     */
    FieldType mapKey() default FieldType.STRING;

    /**
     * Value type for {@link FieldType#MAP}. Ignored otherwise.
     * <p>{@code map<K,V>} : V ∈ all FieldTypes except {@link FieldType#MAP}
     * (no map of map in proto3).</p>
     */
    FieldType mapValue() default FieldType.STRING;
}
