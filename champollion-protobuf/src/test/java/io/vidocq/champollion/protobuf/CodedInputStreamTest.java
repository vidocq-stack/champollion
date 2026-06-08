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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CodedInputStream}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 */
class CodedInputStreamTest {

    private CodedInputStream from(int... bytes) {
        byte[] arr = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) arr[i] = (byte) bytes[i];
        return CodedInputStream.newInstance(arr);
    }

    private CodedInputStream stream(int... bytes) {
        byte[] arr = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) arr[i] = (byte) bytes[i];
        return CodedInputStream.newInstance(new ByteArrayInputStream(arr));
    }

    @Nested
    @DisplayName("Encoding §1 — Base 128 Varints (decode)")
    class Varints {

        @Test
        void zero() throws IOException {
            assertEquals(0, from(0x00).readRawVarint32());
        }

        @Test
        void one() throws IOException {
            assertEquals(1, from(0x01).readRawVarint32());
        }

        @Test
        void one_hundred_fifty() throws IOException {
            assertEquals(150, from(0x96, 0x01).readRawVarint32());
        }

        @Test
        void long_max_value() throws IOException {
            assertEquals(Long.MAX_VALUE,
                    from(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F).readRawVarint64());
        }

        @Test
        void minus_one_long() throws IOException {
            assertEquals(-1L,
                    from(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
                            .readRawVarint64());
        }

        @Test
        void malformed_varint_too_many_bytes() {
            // 11 octets de continuation → erreur après 10.
            CodedInputStream in = from(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF);
            assertThrows(MalformedProtobufException.class, in::readRawVarint64);
        }

        @Test
        void truncated_varint_throws() {
            // continuation à 1 mais flux fini → truncated
            CodedInputStream in = from(0x80);
            assertThrows(MalformedProtobufException.class, in::readRawVarint32);
        }

        @Test
        void stream_decoder_handles_same_vectors() throws IOException {
            assertEquals(150, stream(0x96, 0x01).readRawVarint32());
            assertEquals(Long.MAX_VALUE,
                    stream(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F).readRawVarint64());
        }
    }

    @Nested
    @DisplayName("Encoding §1 — ZigZag (decode)")
    class ZigZag {

        @Test
        void zero_to_zero() {
            assertEquals(0, CodedInputStream.decodeZigZag32(0));
        }

        @Test
        void one_to_minus_one() {
            assertEquals(-1, CodedInputStream.decodeZigZag32(1));
        }

        @Test
        void two_to_one() {
            assertEquals(1, CodedInputStream.decodeZigZag32(2));
        }

        @Test
        void integer_min_value_roundtrip() {
            int encoded = CodedOutputStream.encodeZigZag32(Integer.MIN_VALUE);
            assertEquals(Integer.MIN_VALUE, CodedInputStream.decodeZigZag32(encoded));
        }

        @Test
        void integer_max_value_roundtrip() {
            int encoded = CodedOutputStream.encodeZigZag32(Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, CodedInputStream.decodeZigZag32(encoded));
        }

        @Test
        void long_min_value_roundtrip() {
            long encoded = CodedOutputStream.encodeZigZag64(Long.MIN_VALUE);
            assertEquals(Long.MIN_VALUE, CodedInputStream.decodeZigZag64(encoded));
        }
    }

    @Nested
    @DisplayName("Encoding §I32/I64 — Little Endian fixed widths (decode)")
    class FixedWidth {

        @Test
        void fixed32_little_endian() throws IOException {
            assertEquals(0x12345678,
                    from(0x78, 0x56, 0x34, 0x12).readRawLittleEndian32());
        }

        @Test
        void fixed64_little_endian() throws IOException {
            assertEquals(0x1122334455667788L,
                    from(0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11).readRawLittleEndian64());
        }

        @Test
        void fixed32_truncated_throws() {
            assertThrows(MalformedProtobufException.class, () -> from(0x01, 0x02).readRawLittleEndian32());
        }
    }

    @Nested
    @DisplayName("Encoding §Tag — round trip")
    class Tag {

        @Test
        void read_tag_returns_zero_on_eof() throws IOException {
            assertEquals(0, from().readTag());
        }

        @Test
        void read_tag_decomposes_field_and_wireType() throws IOException {
            // tag pour field=2, wireType=LEN → 0x12
            CodedInputStream in = from(0x12);
            int tag = in.readTag();
            assertEquals(2, WireFormat.getTagFieldNumber(tag));
            assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
        }

        @Test
        void invalid_zero_field_number_throws() {
            // tag avec field_number = 0 → invalide
            assertThrows(MalformedProtobufException.class, () -> from(0x00).readTag());
        }
    }

    @Nested
    @DisplayName("Encoding §LEN — string/bytes")
    class LengthDelimited {

        @Test
        void string_hello() throws IOException {
            // tag (LEN, field 1) | length 5 | "hello"
            CodedInputStream in = from(0x0A, 0x05, 'h', 'e', 'l', 'l', 'o');
            int tag = in.readTag();
            assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
            assertEquals("hello", in.readString());
        }

        @Test
        void empty_string() throws IOException {
            CodedInputStream in = from(0x00);
            assertEquals("", in.readString());
        }

        @Test
        void bytes_roundtrip() throws IOException {
            CodedInputStream in = from(0x03, 0x01, 0x02, 0x03);
            assertArrayEquals(new byte[]{1, 2, 3}, in.readBytes());
        }

        @Test
        void negative_length_throws() {
            // varint that decodes to a value whose signed interpretation < 0
            // -1 in varint = 10 bytes 0xFF...0x01. read_raw_varint32 will cast it as int
            // so we get a number. But negative size → explicit exception via readBytes.
            CodedInputStream in = from(0xFF, 0xFF, 0xFF, 0xFF, 0x0F);
            // This sequence encodes 0xFFFFFFFF (varint32) which as signed int = -1.
            assertThrows(MalformedProtobufException.class, in::readBytes);
        }
    }

    @Nested
    @DisplayName("Roundtrip — full encode/decode loop")
    class RoundTrip {

        @Test
        void int32_via_writeInt32_and_readInt32() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeInt32(1, 12345);
            out.writeInt32(2, -7);
            out.flush();
            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());
            int tag1 = in.readTag();
            assertEquals(1, WireFormat.getTagFieldNumber(tag1));
            assertEquals(12345, in.readInt32());
            int tag2 = in.readTag();
            assertEquals(2, WireFormat.getTagFieldNumber(tag2));
            assertEquals(-7, in.readInt32());
            assertTrue(in.isAtEnd());
        }

        @Test
        void sint32_roundtrip_via_writeSInt32_and_readSInt32() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeSInt32(7, -42);
            out.flush();
            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());
            in.readTag();
            assertEquals(-42, in.readSInt32());
        }

        @Test
        void fixed32_roundtrip() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeFixed32(3, 0xCAFEBABE);
            out.flush();
            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());
            in.readTag();
            assertEquals(0xCAFEBABE, in.readFixed32());
        }

        @Test
        void double_roundtrip() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeDouble(1, 2.718281828);
            out.flush();
            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());
            in.readTag();
            assertEquals(2.718281828, in.readDouble());
        }

        @Test
        void bool_roundtrip() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            out.writeBool(1, true);
            out.writeBool(2, false);
            out.flush();
            CodedInputStream in = CodedInputStream.newInstance(baos.toByteArray());
            in.readTag();
            assertTrue(in.readBool());
            in.readTag();
            assertFalse(in.readBool());
        }
    }

    @Nested
    @DisplayName("Skip & recursion limit")
    class Skip {

        @Test
        void skip_varint_field_consumes_payload() throws IOException {
            // Tag (field 1, varint) | value 150 | tag (field 2, varint) | value 7
            CodedInputStream in = from(0x08, 0x96, 0x01, 0x10, 0x07);
            int tag1 = in.readTag();
            in.skipField(tag1);
            int tag2 = in.readTag();
            assertEquals(2, WireFormat.getTagFieldNumber(tag2));
            assertEquals(7, in.readRawVarint32());
        }

        @Test
        void skip_length_delimited_consumes_payload() throws IOException {
            // Tag (field 1, LEN) | length 5 | payload | tag (field 2, varint) | value 1
            CodedInputStream in = from(0x0A, 0x05, 'h', 'e', 'l', 'l', 'o', 0x10, 0x01);
            int tag1 = in.readTag();
            in.skipField(tag1);
            int tag2 = in.readTag();
            assertEquals(2, WireFormat.getTagFieldNumber(tag2));
        }

        @Test
        void recursion_limit_default_is_100() {
            CodedInputStream in = from();
            assertEquals(CodedInputStream.DEFAULT_RECURSION_LIMIT, 100);
            int old = in.setRecursionLimit(5);
            assertEquals(100, old);
        }

        @Test
        void recursion_limit_exceeded_throws() throws IOException {
            CodedInputStream in = from();
            in.setRecursionLimit(2);
            in.incrementRecursionDepth();
            in.incrementRecursionDepth();
            assertThrows(MalformedProtobufException.class, in::incrementRecursionDepth);
        }
    }
}
