/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code google.protobuf.Value} — dynamically typed JSON value.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#value">
 * Value</a> — oneof of 6 sub-fields (null/number/string/bool/struct/list).
 * Only one sub-field may be non-null at a time.</p>
 *
 * <p>Represented in Java as a record with 6 fields annotated
 * {@code oneofGroup="kind"} + {@code explicitPresence=true} to respect
 * oneof semantics (last-wins on the wire, null/default JSON distinction).</p>
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

    /** Factory: Value representing JSON {@code null}. */
    public static Value ofNull() {
        return new Value(NullValue.NULL_VALUE, null, null, null, null, null);
    }

    /** Factory: Value representing a JSON number. */
    public static Value ofNumber(double n) {
        return new Value(null, n, null, null, null, null);
    }

    /** Factory: Value representing a JSON string. */
    public static Value ofString(String s) {
        return new Value(null, null, s, null, null, null);
    }

    /** Factory: Value representing a JSON boolean. */
    public static Value ofBool(boolean b) {
        return new Value(null, null, null, b, null, null);
    }

    /** Factory: Value representing a JSON Struct object. */
    public static Value ofStruct(Struct s) {
        return new Value(null, null, null, null, s, null);
    }

    /** Factory: Value representing a JSON ListValue array. */
    public static Value ofList(ListValue l) {
        return new Value(null, null, null, null, null, l);
    }
}
