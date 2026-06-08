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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests pour {@link CodedInputStream#pushLimit(int)} / {@link CodedInputStream#popLimit(int)}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/#packed">Encoding §Packed</a>
 * et §Embedded Messages. Le pattern est : lire la taille (varint), pushLimit,
 * lire les éléments un par un jusqu'à {@code isAtEnd()}, puis popLimit.</p>
 */
class SubStreamLimitTest {

    @Nested
    @DisplayName("Packed repeated — LEN payload contenant N varints concaténés")
    class PackedRepeated {

        @Test
        void decode_packed_int32_via_pushLimit() throws IOException {
            // Champ 6, type LEN, payload = [3, 270, 86942] varint-encodés
            // 270 → 0x8E 0x02 ; 86942 → 0x9E 0xA7 0x05
            // payload : 03 8E 02 9E A7 05 (6 octets)
            // header tag : (6 << 3) | 2 = 50 = 0x32
            // length : 06
            byte[] bytes = {0x32, 0x06,
                    0x03,
                    (byte) 0x8E, 0x02,
                    (byte) 0x9E, (byte) 0xA7, 0x05};
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            int tag = in.readTag();
            assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
            assertEquals(6, WireFormat.getTagFieldNumber(tag));

            int size = in.readRawVarint32();
            int oldLimit = in.pushLimit(size);
            List<Integer> values = new ArrayList<>();
            while (!in.isAtEnd()) {
                values.add(in.readInt32());
            }
            in.popLimit(oldLimit);

            assertEquals(List.of(3, 270, 86942), values);
            assertTrue(in.isAtEnd());
        }

        @Test
        void decode_packed_int32_via_stream_decoder() throws IOException {
            byte[] bytes = {0x32, 0x06,
                    0x03,
                    (byte) 0x8E, 0x02,
                    (byte) 0x9E, (byte) 0xA7, 0x05};
            CodedInputStream in = CodedInputStream.newInstance(new ByteArrayInputStream(bytes));
            in.readTag();
            int size = in.readRawVarint32();
            int oldLimit = in.pushLimit(size);
            List<Integer> values = new ArrayList<>();
            while (!in.isAtEnd()) {
                values.add(in.readInt32());
            }
            in.popLimit(oldLimit);
            assertEquals(List.of(3, 270, 86942), values);
            assertTrue(in.isAtEnd());
        }
    }

    @Nested
    @DisplayName("Embedded message — pushLimit englobe le sous-message")
    class EmbeddedMessage {

        @Test
        void parent_field_after_embedded_is_reached_when_popLimit_restores() throws IOException {
            // Construction manuelle : champ 1 (LEN) contient un sous-message de
            // 5 octets [tag varint pour field 7 + value 42], suivi par champ 2
            // (varint) avec value 9 au niveau parent.
            ByteArrayOutputStream sub = new ByteArrayOutputStream();
            CodedOutputStream subOut = CodedOutputStream.newInstance(sub);
            subOut.writeInt32(7, 42);
            subOut.flush();
            byte[] subPayload = sub.toByteArray();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeRawVarint32(subPayload.length);
            out.writeRawBytes(subPayload, 0, subPayload.length);
            out.writeInt32(2, 9);
            out.flush();

            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());

            int parentTag1 = in.readTag();
            assertEquals(1, WireFormat.getTagFieldNumber(parentTag1));
            int size = in.readRawVarint32();
            int oldLimit = in.pushLimit(size);
            int subTag = in.readTag();
            assertEquals(7, WireFormat.getTagFieldNumber(subTag));
            assertEquals(42, in.readInt32());
            assertTrue(in.isAtEnd(), "sub-stream consommé");
            in.popLimit(oldLimit);

            int parentTag2 = in.readTag();
            assertEquals(2, WireFormat.getTagFieldNumber(parentTag2));
            assertEquals(9, in.readInt32());
        }

        @Test
        void pushLimit_beyond_outer_limit_throws() {
            byte[] tiny = new byte[]{0x01, 0x02};
            CodedInputStream in = CodedInputStream.newInstance(tiny);
            assertThrows(MalformedProtobufException.class, () -> in.pushLimit(100));
        }

        @Test
        void getBytesUntilLimit_returns_remaining_inside_pushLimit() throws IOException {
            byte[] bytes = new byte[10];
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            int old = in.pushLimit(5);
            assertEquals(5, in.getBytesUntilLimit());
            in.readRawByte();
            assertEquals(4, in.getBytesUntilLimit());
            in.popLimit(old);
            assertEquals(Integer.MAX_VALUE, in.getBytesUntilLimit());
        }
    }
}
