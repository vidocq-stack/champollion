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
package io.vidocq.champollion.protobuf;

/**
 * Protocol Buffers field type — pair {@code (wire type, semantic encoding)}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#scalar">Proto3 §Scalar Value Types</a>.
 * The wire type alone is not enough: a Java {@code int} can encode as a
 * signed varint (INT32), unsigned (UINT32), zigzag (SINT32), fixed little-endian
 * (FIXED32 / SFIXED32). The {@code @ProtobufField(type = ...)} annotation removes
 * this ambiguity.</p>
 */
public enum FieldType {

    INT32(WireFormat.WIRETYPE_VARINT, true),
    INT64(WireFormat.WIRETYPE_VARINT, true),
    UINT32(WireFormat.WIRETYPE_VARINT, true),
    UINT64(WireFormat.WIRETYPE_VARINT, true),
    SINT32(WireFormat.WIRETYPE_VARINT, true),
    SINT64(WireFormat.WIRETYPE_VARINT, true),
    BOOL(WireFormat.WIRETYPE_VARINT, true),
    ENUM(WireFormat.WIRETYPE_VARINT, true),

    FIXED32(WireFormat.WIRETYPE_FIXED32, true),
    SFIXED32(WireFormat.WIRETYPE_FIXED32, true),
    FLOAT(WireFormat.WIRETYPE_FIXED32, true),

    FIXED64(WireFormat.WIRETYPE_FIXED64, true),
    SFIXED64(WireFormat.WIRETYPE_FIXED64, true),
    DOUBLE(WireFormat.WIRETYPE_FIXED64, true),

    /** UTF-8. Non packable. */
    STRING(WireFormat.WIRETYPE_LENGTH_DELIMITED, false),
    /** Octets bruts. Non packable. */
    BYTES(WireFormat.WIRETYPE_LENGTH_DELIMITED, false),
    /** Embedded message (record annotated @ProtobufMessage). Not packable. */
    MESSAGE(WireFormat.WIRETYPE_LENGTH_DELIMITED, false),
    /**
     * {@code map<K,V>} — encoded as {@code repeated Entry { K key = 1; V value = 2; }}.
     * See <a href="https://protobuf.dev/programming-guides/encoding/#maps">Encoding §maps</a>.
     * Not packable. Expected Java type: {@code Map<K,V>}.
     */
    MAP(WireFormat.WIRETYPE_LENGTH_DELIMITED, false);

    private final int wireType;
    private final boolean packable;

    FieldType(int wireType, boolean packable) {
        this.wireType = wireType;
        this.packable = packable;
    }

    public int wireType() {
        return wireType;
    }

    /** {@code true} if {@code repeated} can be encoded in packed mode (proto3 by default). */
    public boolean packable() {
        return packable;
    }
}
