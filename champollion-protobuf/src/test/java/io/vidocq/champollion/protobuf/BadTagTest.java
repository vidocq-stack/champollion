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

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests M5.6.2 — rejet des tags binaires invalides.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/#structure">
 * Encoding §structure</a> — wire types 6/7 réservés, field_number ∈ [1, 2^29-1],
 * tag varint sur ≤ 5 octets.</p>
 */
class BadTagTest {

    @Test
    @DisplayName("Wire type 6 → MalformedProtobufException")
    void wireType6Rejected() {
        // tag = (1 << 3) | 6 = 0x0E
        CodedInputStream in = CodedInputStream.newInstance(new byte[] { 0x0E, 0x00 });
        assertThrows(MalformedProtobufException.class, in::readTag);
    }

    @Test
    @DisplayName("Wire type 7 → MalformedProtobufException")
    void wireType7Rejected() {
        // tag = (1 << 3) | 7 = 0x0F
        CodedInputStream in = CodedInputStream.newInstance(new byte[] { 0x0F, 0x00 });
        assertThrows(MalformedProtobufException.class, in::readTag);
    }

    @Test
    @DisplayName("Tag overlong (6 octets MSB=1) → MalformedProtobufException")
    void overlongVarintRejected() {
        // 6 octets continuation : 0x80 0x80 0x80 0x80 0x80 0x80 + ... → overlong
        CodedInputStream in = CodedInputStream.newInstance(
                new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                             (byte) 0x80, (byte) 0x80, 0x00 });
        assertThrows(MalformedProtobufException.class, in::readTag);
    }

    @Test
    @DisplayName("Zero tag → MalformedProtobufException")
    void zeroTagRejected() {
        CodedInputStream in = CodedInputStream.newInstance(new byte[] { 0x00 });
        assertThrows(MalformedProtobufException.class, in::readTag);
    }
}
