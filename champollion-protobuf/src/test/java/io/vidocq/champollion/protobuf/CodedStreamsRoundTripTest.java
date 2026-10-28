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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Every tagged writer of {@link CodedOutputStream} read back by the matching reader of {@link CodedInputStream}, over
 * the array and the stream implementations, including buffers far smaller than the values.
 */
class CodedStreamsRoundTripTest {

    private static final String TEXT = "héllo, protobuf";
    private static final byte[] BLOB = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    private static void writeAll(CodedOutputStream out) throws IOException {
        out.writeInt32(1, -7);
        out.writeUInt32(2, -1);
        out.writeSInt32(3, -3);
        out.writeInt64(4, Long.MIN_VALUE);
        out.writeUInt64(5, -1L);
        out.writeSInt64(6, -9L);
        out.writeFixed32(7, 0xCAFEBABE);
        out.writeSFixed32(8, -2);
        out.writeFloat(9, 1.25f);
        out.writeFixed64(10, 0x0102030405060708L);
        out.writeSFixed64(11, -5L);
        out.writeDouble(12, -0.5);
        out.writeBool(13, true);
        out.writeEnum(14, 3);
        out.writeBytes(15, BLOB);
        out.writeString(16, TEXT);
    }

    private static void readAll(CodedInputStream in) throws IOException {
        assertEquals(WireFormat.makeTag(1, WireFormat.WIRETYPE_VARINT), in.readTag());
        assertEquals(-7, in.readInt32());
        in.readTag();
        assertEquals(-1, in.readUInt32());
        in.readTag();
        assertEquals(-3, in.readSInt32());
        in.readTag();
        assertEquals(Long.MIN_VALUE, in.readInt64());
        in.readTag();
        assertEquals(-1L, in.readUInt64());
        in.readTag();
        assertEquals(-9L, in.readSInt64());
        in.readTag();
        assertEquals(0xCAFEBABE, in.readFixed32());
        in.readTag();
        assertEquals(-2, in.readSFixed32());
        in.readTag();
        assertEquals(1.25f, in.readFloat());
        in.readTag();
        assertEquals(0x0102030405060708L, in.readFixed64());
        in.readTag();
        assertEquals(-5L, in.readSFixed64());
        in.readTag();
        assertEquals(-0.5, in.readDouble());
        in.readTag();
        assertTrue(in.readBool());
        in.readTag();
        assertEquals(3, in.readEnum());
        in.readTag();
        assertArrayEquals(BLOB, in.readBytes());
        assertEquals(WireFormat.makeTag(16, WireFormat.WIRETYPE_LENGTH_DELIMITED), in.readTag());
        assertEquals(TEXT, in.readString());
        assertEquals(0, in.readTag());
        assertTrue(in.isAtEnd());
    }

    private static byte[] encoded() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes, 3);
        writeAll(out);
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    void array_and_small_buffer_stream_encoders_produce_the_same_bytes() throws IOException {
        byte[] array = new byte[256];
        CodedOutputStream out = CodedOutputStream.newInstance(array, 0, array.length);
        writeAll(out);
        out.flush();
        byte[] streamed = encoded();
        assertEquals(streamed.length, out.getTotalBytesWritten());
        assertArrayEquals(streamed, java.util.Arrays.copyOf(array, out.getTotalBytesWritten()));
    }

    @Test
    void stream_encoder_counts_every_byte_written() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes, 4);
        out.writeRawByte(0);
        out.writeRawBytes(BLOB, 0, BLOB.length);
        out.writeRawBytes(BLOB, 0, 2);
        out.flush();
        assertEquals(1 + BLOB.length + 2, bytes.size());
        assertEquals(bytes.size(), out.getTotalBytesWritten());
    }

    @Test
    void default_stream_encoder_and_decoder_round_trip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        writeAll(out);
        out.flush();
        readAll(CodedInputStream.newInstance(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 4096})
    void stream_decoder_reads_everything_whatever_its_buffer_size(int bufferSize) throws IOException {
        readAll(CodedInputStream.newInstance(new ByteArrayInputStream(encoded()), bufferSize));
    }

    @Test
    void array_decoder_reads_a_slice() throws IOException {
        byte[] data = encoded();
        byte[] padded = new byte[data.length + 4];
        System.arraycopy(data, 0, padded, 2, data.length);
        readAll(CodedInputStream.newInstance(padded, 2, data.length));
    }

    @Test
    void factories_validate_their_arguments() {
        byte[] data = new byte[4];
        InputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IndexOutOfBoundsException.class, () -> CodedInputStream.newInstance(data, 3, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> CodedInputStream.newInstance(data, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> CodedInputStream.newInstance(in, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> CodedOutputStream.newInstance(data, 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> CodedOutputStream.newInstance(data, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> CodedOutputStream.newInstance(bytes, 0));
    }

    @Test
    void array_encoder_refuses_to_overflow_its_buffer() throws IOException {
        CodedOutputStream out = CodedOutputStream.newInstance(new byte[2]);
        out.writeRawByte(1);
        assertThrows(IOException.class, () -> out.writeRawBytes(BLOB, 0, 2));
    }

    @Test
    void recursion_limit_is_configurable_and_enforced() throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(new byte[0]);
        assertEquals(CodedInputStream.DEFAULT_RECURSION_LIMIT, in.setRecursionLimit(1));
        in.incrementRecursionDepth();
        assertThrows(MalformedProtobufException.class, in::incrementRecursionDepth);
        in.decrementRecursionDepth();
        in.decrementRecursionDepth();
        in.incrementRecursionDepth();
        assertThrows(IllegalArgumentException.class, () -> in.setRecursionLimit(-1));
    }

    @Test
    void last_tag_check_detects_a_mismatch() throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(new byte[] {0x08, 0x01});
        int tag = in.readTag();
        in.checkLastTagWas(tag);
        assertThrows(MalformedProtobufException.class, () -> in.checkLastTagWas(tag + 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00",             // tag 0 inside the message
            "07",             // field number 0
            "0E01",           // wire type 6
            "0F01",           // wire type 7
            "8080808010"      // fifth byte above four bits
    })
    void malformed_tags_are_rejected(String hex) {
        CodedInputStream in = CodedInputStream.newInstance(hex(hex));
        assertThrows(MalformedProtobufException.class, in::readTag);
    }

    @Test
    void a_standalone_end_group_cannot_be_skipped() throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(hex("0C"));
        int tag = in.readTag();
        assertThrows(MalformedProtobufException.class, () -> in.skipField(tag));
    }

    @Test
    void skip_field_records_every_wire_type_and_skips_groups() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeUInt64(1, 300);
        out.writeFixed64(2, 7L);
        out.writeBytes(3, BLOB);
        out.writeFixed32(4, 9);
        out.writeTag(5, WireFormat.WIRETYPE_START_GROUP);
        out.writeInt32(6, 1);
        out.writeTag(5, WireFormat.WIRETYPE_END_GROUP);
        out.flush();

        UnknownFieldSet.Builder recorder = UnknownFieldSet.newBuilder();
        CodedInputStream in = CodedInputStream.newInstance(new ByteArrayInputStream(bytes.toByteArray()), 2);
        int tag;
        while ((tag = in.readTag()) != 0) {
            assertTrue(in.skipField(tag, recorder));
        }
        UnknownFieldSet set = recorder.build();
        assertEquals(List.of(300L, 1L), concat(set.get(1).varints(), set.get(6).varints()));
        assertEquals(List.of(7L), set.get(2).fixed64s());
        assertArrayEquals(BLOB, set.get(3).lengthDelimited().get(0));
        assertEquals(List.of(9), set.get(4).fixed32s());
    }

    @Test
    void a_group_must_be_closed_by_its_own_end_tag() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeTag(5, WireFormat.WIRETYPE_START_GROUP);
        out.writeTag(6, WireFormat.WIRETYPE_END_GROUP);
        out.flush();
        CodedInputStream wrongEnd = CodedInputStream.newInstance(bytes.toByteArray());
        int tag = wrongEnd.readTag();
        assertThrows(MalformedProtobufException.class, () -> wrongEnd.skipField(tag));

        CodedInputStream truncated = CodedInputStream.newInstance(hex("2B"));
        int start = truncated.readTag();
        assertThrows(MalformedProtobufException.class, () -> truncated.skipField(start));
    }

    @Test
    void negative_lengths_are_rejected() {
        byte[] negative = hex("FFFFFFFF0F");
        assertThrows(MalformedProtobufException.class, () -> CodedInputStream.newInstance(negative).readString());
        assertThrows(MalformedProtobufException.class,
                () -> CodedInputStream.newInstance(negative).readStringRequireUtf8());
        assertThrows(MalformedProtobufException.class, () -> CodedInputStream.newInstance(negative).readBytes());
        assertThrows(MalformedProtobufException.class,
                () -> CodedInputStream.newInstance(negative).skipField(0x1A));
    }

    @Test
    void stream_decoder_reports_truncation_and_limits() throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(new ByteArrayInputStream(BLOB), 3);
        assertEquals(Integer.MAX_VALUE, in.getBytesUntilLimit());
        int old = in.pushLimit(5);
        assertEquals(5, in.getBytesUntilLimit());
        in.skipRawBytes(4);
        assertEquals(1, in.getBytesUntilLimit());
        assertThrows(MalformedProtobufException.class, () -> in.readRawBytes(2));
        assertThrows(MalformedProtobufException.class, () -> in.skipRawBytes(2));
        assertThrows(MalformedProtobufException.class, () -> in.pushLimit(2));
        assertThrows(MalformedProtobufException.class, () -> in.pushLimit(-1));
        assertThrows(MalformedProtobufException.class, () -> in.readRawBytes(-1));
        assertThrows(MalformedProtobufException.class, () -> in.skipRawBytes(-1));
        assertEquals(0, in.readRawBytes(0).length);
        assertEquals(5, in.readRawByte());
        assertTrue(in.isAtEnd());
        assertThrows(MalformedProtobufException.class, in::readRawByte);
        in.popLimit(old);
        assertFalse(in.isAtEnd());
        in.skipRawBytes(BLOB.length - 5);
        assertTrue(in.isAtEnd());
        assertThrows(MalformedProtobufException.class, in::readRawByte);
    }

    @Test
    void stream_decoder_rejects_reads_past_the_end_of_the_stream() {
        assertThrows(MalformedProtobufException.class,
                () -> CodedInputStream.newInstance(new ByteArrayInputStream(BLOB), 4).readRawBytes(BLOB.length + 1));
        assertThrows(MalformedProtobufException.class,
                () -> CodedInputStream.newInstance(new ByteArrayInputStream(BLOB), 4).skipRawBytes(BLOB.length + 1));
        assertThrows(MalformedProtobufException.class,
                () -> CodedInputStream.newInstance(new ByteArrayInputStream(hex("FFFFFFFFFFFFFFFFFFFF01")), 4)
                        .readRawVarint64());
    }

    @Test
    void array_decoder_skips_raw_bytes_within_bounds() throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(BLOB);
        in.skipRawBytes(BLOB.length - 1);
        assertEquals(12, in.readRawByte());
        assertThrows(MalformedProtobufException.class, () -> in.skipRawBytes(1));
        assertThrows(MalformedProtobufException.class, () -> in.skipRawBytes(-1));
    }

    private static List<Long> concat(List<Long> a, List<Long> b) {
        List<Long> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
