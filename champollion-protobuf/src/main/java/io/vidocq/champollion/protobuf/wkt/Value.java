package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code google.protobuf.Value} — valeur JSON-typée dynamique.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#value">
 * Value</a> — oneof de 6 sub-fields (null/number/string/bool/struct/list).
 * Un seul sub-field doit être non-null à la fois.</p>
 *
 * <p>Représenté en Java comme un record avec 6 champs annotés
 * {@code oneofGroup="kind"} + {@code explicitPresence=true} pour respecter
 * la sémantique oneof (last-wins sur la wire, distinction null/default JSON).</p>
 */
@ProtobufMessage("google.protobuf.Value")
public record Value(
        @ProtobufField(number = 1, type = FieldType.ENUM,
                       explicitPresence = true, oneofGroup = "kind")
        NullValue nullValue,
        @ProtobufField(number = 2, type = FieldType.DOUBLE,
                       explicitPresence = true, oneofGroup = "kind")
        Double numberValue,
        @ProtobufField(number = 3, type = FieldType.STRING,
                       explicitPresence = true, oneofGroup = "kind")
        String stringValue,
        @ProtobufField(number = 4, type = FieldType.BOOL,
                       explicitPresence = true, oneofGroup = "kind")
        Boolean boolValue,
        @ProtobufField(number = 5, type = FieldType.MESSAGE,
                       explicitPresence = true, oneofGroup = "kind")
        Struct structValue,
        @ProtobufField(number = 6, type = FieldType.MESSAGE,
                       explicitPresence = true, oneofGroup = "kind")
        ListValue listValue
) implements Message {

    /** Factory : Value représentant {@code null} JSON. */
    public static Value ofNull() {
        return new Value(NullValue.NULL_VALUE, null, null, null, null, null);
    }

    /** Factory : Value représentant un number JSON. */
    public static Value ofNumber(double n) {
        return new Value(null, n, null, null, null, null);
    }

    /** Factory : Value représentant une string JSON. */
    public static Value ofString(String s) {
        return new Value(null, null, s, null, null, null);
    }

    /** Factory : Value représentant un boolean JSON. */
    public static Value ofBool(boolean b) {
        return new Value(null, null, null, b, null, null);
    }

    /** Factory : Value représentant un Struct JSON object. */
    public static Value ofStruct(Struct s) {
        return new Value(null, null, null, null, s, null);
    }

    /** Factory : Value représentant un ListValue JSON array. */
    public static Value ofList(ListValue l) {
        return new Value(null, null, null, null, null, l);
    }
}
