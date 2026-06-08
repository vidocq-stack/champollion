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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M4.3.1 — {@code Utf8Validation.VERIFY} (Edition 2023) :
 * {@link CodedInputStream#readStringRequireUtf8()} doit rejeter les séquences
 * UTF-8 mal formées (cohérent avec {@code Features.PROTO3_DEFAULTS}), tandis
 * que {@link CodedInputStream#readString()} conserve le comportement lax
 * (remplacement {@code U+FFFD}) pour {@code Utf8Validation.NONE} (proto2).
 *
 * <p>Spec : <a href="https://protobuf.dev/editions/features/#utf8_validation">
 * features.utf8_validation</a> et RFC 3629 §3.</p>
 */
class Utf8ValidationTest {

    /** {@code 0xC3 0x28} — start byte 2-octet sans continuation valide. RFC 3629 §3. */
    private static final byte[] INVALID_UTF8 = new byte[] { (byte) 0xC3, (byte) 0x28 };

    /** Length-delimited payload : {@code [len=2][C3 28]}. */
    private static byte[] lenPrefixed(byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    @Nested
    @DisplayName("readString — mode lax (Utf8Validation.NONE)")
    class Lax {

        @Test
        void invalid_utf8_returns_replacement_character() throws Exception {
            CodedInputStream in = CodedInputStream.newInstance(lenPrefixed(INVALID_UTF8));
            String s = in.readString();
            assertNotNull(s, "readString lax ne doit jamais retourner null");
            // U+FFFD remplace la séquence invalide, ASCII '(' reste tel quel.
            assertEquals("�(", s);
        }

        @Test
        void valid_utf8_round_trips() throws Exception {
            byte[] utf8 = "héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            CodedInputStream in = CodedInputStream.newInstance(lenPrefixed(utf8));
            assertEquals("héllo", in.readString());
        }
    }

    @Nested
    @DisplayName("readStringRequireUtf8 — mode strict (Utf8Validation.VERIFY)")
    class Strict {

        @Test
        void invalid_utf8_throws_malformed() {
            CodedInputStream in = CodedInputStream.newInstance(lenPrefixed(INVALID_UTF8));
            assertThrows(MalformedProtobufException.class, in::readStringRequireUtf8);
        }

        @Test
        void valid_utf8_round_trips() throws Exception {
            byte[] utf8 = "héllo🐉".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            CodedInputStream in = CodedInputStream.newInstance(lenPrefixed(utf8));
            assertEquals("héllo🐉", in.readStringRequireUtf8());
        }

        @Test
        void empty_string_passes() throws Exception {
            CodedInputStream in = CodedInputStream.newInstance(new byte[] { 0 });
            assertEquals("", in.readStringRequireUtf8());
        }

        @Test
        void overlong_encoding_rejected() {
            // U+002F slash encodé en 2 octets ("modified UTF-8" overlong) : C0 AF.
            // RFC 3629 §10 — explicitement interdit.
            byte[] overlong = new byte[] { (byte) 0xC0, (byte) 0xAF };
            CodedInputStream in = CodedInputStream.newInstance(lenPrefixed(overlong));
            assertThrows(MalformedProtobufException.class, in::readStringRequireUtf8);
        }
    }

    @Nested
    @DisplayName("writeStringNoTag — strict côté écriture")
    class Write {

        @Test
        void unpaired_high_surrogate_rejected() {
            // "\uD800" sans low surrogate — invalide UTF-16, donc inconvertible UTF-8.
            // Conformance Google : Required.Proto3.ProtobufOutput.InvalidUtf8.
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(sink);
            assertThrows(MalformedProtobufException.class, () -> out.writeStringNoTag("\uD800"));
        }

        @Test
        void unpaired_low_surrogate_rejected() {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(sink);
            assertThrows(MalformedProtobufException.class, () -> out.writeStringNoTag("\uDC00"));
        }

        @Test
        void valid_string_writes_normally() throws Exception {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(sink);
            out.writeStringNoTag("héllo🐉");
            out.flush();
            // Round-trip lecture stricte
            CodedInputStream in = CodedInputStream.newInstance(sink.toByteArray());
            assertEquals("héllo🐉", in.readStringRequireUtf8());
        }
    }
}
