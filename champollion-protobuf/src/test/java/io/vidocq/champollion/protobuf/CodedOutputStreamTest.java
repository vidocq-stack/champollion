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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CodedOutputStream}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 *
 * <p>Vecteurs canoniques de la spec : {@code 0 → 00}, {@code 1 → 01},
 * {@code 150 → 96 01}, {@code -1 (int64) → FF FF FF FF FF FF FF FF FF 01}.</p>
 */
class CodedOutputStreamTest {

    private byte[] encode(Encoder fn) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        fn.run(out);
        out.flush();
        return baos.toByteArray();
    }

    @FunctionalInterface
    interface Encoder {
        void run(CodedOutputStream out) throws IOException;
    }

    @Nested
    @DisplayName("Encoding §1 — Base 128 Varints")
    class Varints {

        @Test
        void zero_is_one_byte() throws IOException {
            byte[] bytes = encode(o -> o.writeRawVarint32(0));
            assertArrayEquals(new byte[]{0x00}, bytes);
        }

        @Test
        void one_is_one_byte() throws IOException {
            byte[] bytes = encode(o -> o.writeRawVarint32(1));
            assertArrayEquals(new byte[]{0x01}, bytes);
        }

        @Test
        void one_hundred_fifty_is_96_01() throws IOException {
            // Vecteur canonique de la doc protobuf.dev.
            byte[] bytes = encode(o -> o.writeRawVarint32(150));
            assertArrayEquals(new byte[]{(byte) 0x96, 0x01}, bytes);
        }

        @Test
        void max_uint32_is_five_bytes() throws IOException {
            byte[] bytes = encode(o -> o.writeRawVarint32(0xFFFFFFFF));
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, bytes);
        }

        @Test
        void long_max_value_is_nine_bytes() throws IOException {
            byte[] bytes = encode(o -> o.writeRawVarint64(Long.MAX_VALUE));
            assertEquals(9, bytes.length);
            // 0x7FFF_FFFF_FFFF_FFFF : 9 octets, dernier 0x7F.
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) 0xFF, bytes[i], "byte " + i);
            }
            assertEquals((byte) 0x7F, bytes[8]);
        }

        @Test
        void minus_one_long_is_ten_bytes() throws IOException {
            byte[] bytes = encode(o -> o.writeRawVarint64(-1L));
            assertEquals(10, bytes.length);
            for (int i = 0; i < 9; i++) {
                assertEquals((byte) 0xFF, bytes[i], "byte " + i);
            }
            assertEquals((byte) 0x01, bytes[9]);
        }

        @Test
        void negative_int32_via_writeInt32NoTag_is_ten_bytes_sign_extended() throws IOException {
            // Sign-extension proto2 : a negative int32 occupies 10 bytes in varint.
            byte[] bytes = encode(o -> o.writeInt32NoTag(-1));
            assertEquals(10, bytes.length);
        }

        @Test
        void computeRawVarint32Size_matches_actual_encoding() throws IOException {
            int[] samples = {0, 1, 127, 128, 16383, 16384, 1 << 21 - 1, 1 << 21, Integer.MAX_VALUE};
            for (int sample : samples) {
                byte[] bytes = encode(o -> o.writeRawVarint32(sample));
                assertEquals(bytes.length, CodedOutputStream.computeRawVarint32Size(sample),
                        "varint32 size for " + sample);
            }
        }
    }

    @Nested
    @DisplayName("Encoding §1 — ZigZag")
    class ZigZag {

        @Test
        void zero_to_zero() {
            assertEquals(0, CodedOutputStream.encodeZigZag32(0));
        }

        @Test
        void minus_one_to_one() {
            assertEquals(1, CodedOutputStream.encodeZigZag32(-1));
        }

        @Test
        void one_to_two() {
            assertEquals(2, CodedOutputStream.encodeZigZag32(1));
        }

        @Test
        void minus_two_to_three() {
            assertEquals(3, CodedOutputStream.encodeZigZag32(-2));
        }

        @Test
        void integer_max_value() {
            // (MAX_VALUE << 1) ^ (MAX_VALUE >> 31) = -2 ^ 0 = -2 = 0xFFFFFFFE (unsigned 4294967294)
            assertEquals(0xFFFFFFFE, CodedOutputStream.encodeZigZag32(Integer.MAX_VALUE));
        }

        @Test
        void integer_min_value() {
            // (MIN_VALUE << 1) ^ (MIN_VALUE >> 31) = 0 ^ -1 = -1 = 0xFFFFFFFF
            assertEquals(0xFFFFFFFF, CodedOutputStream.encodeZigZag32(Integer.MIN_VALUE));
        }

        @Test
        void long_min_value() {
            assertEquals(0xFFFFFFFFFFFFFFFFL, CodedOutputStream.encodeZigZag64(Long.MIN_VALUE));
        }
    }

    @Nested
    @DisplayName("Encoding §I32/I64 — Little Endian fixed widths")
    class FixedWidth {

        @Test
        void fixed32_is_little_endian() throws IOException {
            // 0x12345678 → LE : 78 56 34 12
            byte[] bytes = encode(o -> o.writeRawLittleEndian32(0x12345678));
            assertArrayEquals(new byte[]{0x78, 0x56, 0x34, 0x12}, bytes);
        }

        @Test
        void fixed64_is_little_endian() throws IOException {
            // 0x1122334455667788 → LE : 88 77 66 55 44 33 22 11
            byte[] bytes = encode(o -> o.writeRawLittleEndian64(0x1122334455667788L));
            assertArrayEquals(
                    new byte[]{(byte) 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11}, bytes);
        }

        @Test
        void float_round_trips_via_intBits() throws IOException {
            byte[] bytes = encode(o -> o.writeFloatNoTag(3.14f));
            assertEquals(4, bytes.length);
            int reread = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
                    | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
            assertEquals(3.14f, Float.intBitsToFloat(reread));
        }

        @Test
        void double_round_trips_via_longBits() throws IOException {
            byte[] bytes = encode(o -> o.writeDoubleNoTag(2.718281828));
            assertEquals(8, bytes.length);
        }
    }

    @Nested
    @DisplayName("Encoding §Tags — varint(field<<3 | wire)")
    class Tags {

        @Test
        void field_1_varint_emits_single_byte_08() throws IOException {
            byte[] bytes = encode(o -> o.writeTag(1, WireFormat.WIRETYPE_VARINT));
            assertArrayEquals(new byte[]{0x08}, bytes);
        }

        @Test
        void field_2_length_delimited_emits_single_byte_12() throws IOException {
            byte[] bytes = encode(o -> o.writeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED));
            assertArrayEquals(new byte[]{0x12}, bytes);
        }

        @Test
        void field_16_varint_takes_two_bytes() throws IOException {
            byte[] bytes = encode(o -> o.writeTag(16, WireFormat.WIRETYPE_VARINT));
            assertEquals(2, bytes.length);
        }
    }

    @Nested
    @DisplayName("Encoding §LEN — length-delimited string/bytes")
    class LengthDelimited {

        @Test
        void string_hello_field_1() throws IOException {
            // tag (08+02=0A pour wireType=LEN) | length 05 | h e l l o
            byte[] bytes = encode(o -> o.writeString(1, "hello"));
            assertArrayEquals(
                    new byte[]{0x0A, 0x05, 'h', 'e', 'l', 'l', 'o'}, bytes);
        }

        @Test
        void empty_string() throws IOException {
            byte[] bytes = encode(o -> o.writeString(1, ""));
            assertArrayEquals(new byte[]{0x0A, 0x00}, bytes);
        }

        @Test
        void utf8_string() throws IOException {
            // "é" (U+00E9) = C3 A9 en UTF-8
            byte[] bytes = encode(o -> o.writeStringNoTag("é"));
            assertArrayEquals(new byte[]{0x02, (byte) 0xC3, (byte) 0xA9}, bytes);
        }
    }

    @Nested
    @DisplayName("ArrayEncoder — buffer overflow")
    class ArrayOverflow {

        @Test
        void writing_past_capacity_throws() {
            byte[] tiny = new byte[2];
            CodedOutputStream out = CodedOutputStream.newInstance(tiny);
            assertThrows(IOException.class, () -> {
                out.writeRawVarint64(Long.MAX_VALUE); // 9 octets > 2
            });
        }

        @Test
        void totalBytesWritten_tracks_position() throws IOException {
            byte[] buffer = new byte[16];
            CodedOutputStream out = CodedOutputStream.newInstance(buffer);
            out.writeTag(1, WireFormat.WIRETYPE_VARINT);
            out.writeInt32NoTag(150);
            assertEquals(3, out.getTotalBytesWritten()); // tag(1) + varint(2)
        }
    }
}
