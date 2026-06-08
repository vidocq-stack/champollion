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
 * Wrappers {@code google.protobuf.*Value} — boxed versions of scalars
 * allowing explicit presence to be expressed in proto3.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#wrapper-types">Wrapper types</a>.
 * Canonical JSON: the <b>primitive value directly</b>, without an object wrapper
 * (e.g. {@code 42} and not {@code {"value":42}}).</p>
 */
public final class Wrappers {

    private Wrappers() {}

    @ProtobufMessage("google.protobuf.DoubleValue")
    public record DoubleValue(@ProtobufField(number = 1, type = FieldType.DOUBLE) double value)
            implements Message {}

    @ProtobufMessage("google.protobuf.FloatValue")
    public record FloatValue(@ProtobufField(number = 1, type = FieldType.FLOAT) float value)
            implements Message {}

    @ProtobufMessage("google.protobuf.Int64Value")
    public record Int64Value(@ProtobufField(number = 1, type = FieldType.INT64) long value)
            implements Message {}

    @ProtobufMessage("google.protobuf.UInt64Value")
    public record UInt64Value(@ProtobufField(number = 1, type = FieldType.UINT64) long value)
            implements Message {}

    @ProtobufMessage("google.protobuf.Int32Value")
    public record Int32Value(@ProtobufField(number = 1, type = FieldType.INT32) int value)
            implements Message {}

    @ProtobufMessage("google.protobuf.UInt32Value")
    public record UInt32Value(@ProtobufField(number = 1, type = FieldType.UINT32) int value)
            implements Message {}

    @ProtobufMessage("google.protobuf.BoolValue")
    public record BoolValue(@ProtobufField(number = 1, type = FieldType.BOOL) boolean value)
            implements Message {}

    @ProtobufMessage("google.protobuf.StringValue")
    public record StringValue(@ProtobufField(number = 1, type = FieldType.STRING) String value)
            implements Message {}

    @ProtobufMessage("google.protobuf.BytesValue")
    public record BytesValue(@ProtobufField(number = 1, type = FieldType.BYTES) byte[] value)
            implements Message {}
}
