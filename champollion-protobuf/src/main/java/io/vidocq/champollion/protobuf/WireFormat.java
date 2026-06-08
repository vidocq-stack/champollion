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
 * Protocol Buffers wire-format constants and utilities.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 *
 * <p>A tag is encoded as {@code varint((field_number << 3) | wire_type)};
 * valid proto3 / Editions 2023 wire types are {@link #WIRETYPE_VARINT},
 * {@link #WIRETYPE_FIXED64}, {@link #WIRETYPE_LENGTH_DELIMITED}, {@link #WIRETYPE_FIXED32}.
 * {@link #WIRETYPE_START_GROUP} / {@link #WIRETYPE_END_GROUP} are kept for
 * proto2 compatibility and the {@code message_encoding=DELIMITED} feature in Editions 2023.</p>
 */
public final class WireFormat {

    public static final int WIRETYPE_VARINT = 0;
    public static final int WIRETYPE_FIXED64 = 1;
    public static final int WIRETYPE_LENGTH_DELIMITED = 2;
    public static final int WIRETYPE_START_GROUP = 3;
    public static final int WIRETYPE_END_GROUP = 4;
    public static final int WIRETYPE_FIXED32 = 5;

    public static final int TAG_TYPE_BITS = 3;
    public static final int TAG_TYPE_MASK = (1 << TAG_TYPE_BITS) - 1;

    /** Smallest legal field_number (spec §2). */
    public static final int FIRST_FIELD_NUMBER = 1;
    /** Largest legal field_number: 2^29 - 1 (spec §2). */
    public static final int MAX_FIELD_NUMBER = (1 << 29) - 1;

    private WireFormat() {}

    /** {@code (fieldNumber << 3) | wireType}, ready to be encoded as a varint. */
    public static int makeTag(int fieldNumber, int wireType) {
        return (fieldNumber << TAG_TYPE_BITS) | wireType;
    }

    public static int getTagFieldNumber(int tag) {
        return tag >>> TAG_TYPE_BITS;
    }

    public static int getTagWireType(int tag) {
        return tag & TAG_TYPE_MASK;
    }
}
