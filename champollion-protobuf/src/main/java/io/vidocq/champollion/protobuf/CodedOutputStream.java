package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encode des primitives Protocol Buffers vers un flux ou un {@code byte[]}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 *
 * <p>Deux implémentations concrètes : array-backed (rapide, copie minimale) et
 * stream-backed (wraps {@link OutputStream}). Nomenclature mentalement alignée
 * sur {@code com.google.protobuf.CodedOutputStream}.</p>
 */
public abstract sealed class CodedOutputStream permits CodedOutputStream.ArrayEncoder,
                                                       CodedOutputStream.StreamEncoder {

    public static CodedOutputStream newInstance(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        return new ArrayEncoder(buffer, 0, buffer.length);
    }

    public static CodedOutputStream newInstance(byte[] buffer, int offset, int length) {
        Objects.requireNonNull(buffer, "buffer");
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "buffer.length=" + buffer.length + ", offset=" + offset + ", length=" + length);
        }
        return new ArrayEncoder(buffer, offset, length);
    }

    public static CodedOutputStream newInstance(OutputStream out) {
        Objects.requireNonNull(out, "out");
        return new StreamEncoder(out, 4096);
    }

    public static CodedOutputStream newInstance(OutputStream out, int bufferSize) {
        Objects.requireNonNull(out, "out");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be > 0");
        }
        return new StreamEncoder(out, bufferSize);
    }

    // ------------------------------------------------------------------ Varints

    /**
     * Encode {@code value} en varint sur 1 à 10 octets (MSB = continuation bit).
     *
     * <p>Spec §1 Base 128 Varints — chaque octet contient 7 bits de payload (low
     * 7 bits) + 1 bit de continuation (high bit).</p>
     */
    public final void writeRawVarint32(int value) throws IOException {
        // Encode {@code value} sur 1 à 5 octets en interprétant value comme unsigned 32-bit.
        // Le sign-extension à 10 octets exigé pour un {@code int32} négatif est appliqué
        // par {@link #writeInt32NoTag(int)} qui délègue alors à {@link #writeRawVarint64(long)}.
        while (true) {
            if ((value & ~0x7F) == 0) {
                writeRawByte(value);
                return;
            }
            writeRawByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public final void writeRawVarint64(long value) throws IOException {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                writeRawByte((int) value);
                return;
            }
            writeRawByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public final void writeRawLittleEndian32(int value) throws IOException {
        writeRawByte(value & 0xFF);
        writeRawByte((value >>> 8) & 0xFF);
        writeRawByte((value >>> 16) & 0xFF);
        writeRawByte((value >>> 24) & 0xFF);
    }

    public final void writeRawLittleEndian64(long value) throws IOException {
        writeRawByte((int) (value & 0xFF));
        writeRawByte((int) ((value >>> 8) & 0xFF));
        writeRawByte((int) ((value >>> 16) & 0xFF));
        writeRawByte((int) ((value >>> 24) & 0xFF));
        writeRawByte((int) ((value >>> 32) & 0xFF));
        writeRawByte((int) ((value >>> 40) & 0xFF));
        writeRawByte((int) ((value >>> 48) & 0xFF));
        writeRawByte((int) ((value >>> 56) & 0xFF));
    }

    // ------------------------------------------------------------------ Zigzag

    /** {@code (n << 1) ^ (n >> 31)} — spec §1 ZigZag Encoding. */
    public static int encodeZigZag32(int n) {
        return (n << 1) ^ (n >> 31);
    }

    /** {@code (n << 1) ^ (n >> 63)} — spec §1 ZigZag Encoding. */
    public static long encodeZigZag64(long n) {
        return (n << 1) ^ (n >> 63);
    }

    // ------------------------------------------------------------------ Tags

    public final void writeTag(int fieldNumber, int wireType) throws IOException {
        writeRawVarint32(WireFormat.makeTag(fieldNumber, wireType));
    }

    // ------------------------------------------------------------------ Scalaires typés

    public final void writeInt32NoTag(int value) throws IOException {
        if (value >= 0) {
            writeRawVarint32(value);
        } else {
            writeRawVarint64(value); // sign-extend, 10 octets
        }
    }

    public final void writeUInt32NoTag(int value) throws IOException {
        writeRawVarint32(value);
    }

    public final void writeSInt32NoTag(int value) throws IOException {
        writeRawVarint32(encodeZigZag32(value));
    }

    public final void writeInt64NoTag(long value) throws IOException {
        writeRawVarint64(value);
    }

    public final void writeUInt64NoTag(long value) throws IOException {
        writeRawVarint64(value);
    }

    public final void writeSInt64NoTag(long value) throws IOException {
        writeRawVarint64(encodeZigZag64(value));
    }

    public final void writeFixed32NoTag(int value) throws IOException {
        writeRawLittleEndian32(value);
    }

    public final void writeSFixed32NoTag(int value) throws IOException {
        writeRawLittleEndian32(value);
    }

    public final void writeFloatNoTag(float value) throws IOException {
        writeRawLittleEndian32(Float.floatToRawIntBits(value));
    }

    public final void writeFixed64NoTag(long value) throws IOException {
        writeRawLittleEndian64(value);
    }

    public final void writeSFixed64NoTag(long value) throws IOException {
        writeRawLittleEndian64(value);
    }

    public final void writeDoubleNoTag(double value) throws IOException {
        writeRawLittleEndian64(Double.doubleToRawLongBits(value));
    }

    public final void writeBoolNoTag(boolean value) throws IOException {
        writeRawByte(value ? 1 : 0);
    }

    public final void writeEnumNoTag(int value) throws IOException {
        writeInt32NoTag(value);
    }

    public final void writeBytesNoTag(byte[] value) throws IOException {
        writeRawVarint32(value.length);
        writeRawBytes(value, 0, value.length);
    }

    /**
     * Encode {@code value} en UTF-8 strict — toute séquence UTF-16 mal formée
     * (surrogate non-pairé) lève {@link MalformedProtobufException}.
     *
     * <p>Cohérent avec {@code features.utf8_validation = VERIFY} (défaut proto3 / Edition
     * 2023) et avec la conformance Google {@code Required.Proto3.ProtobufOutput.InvalidUtf8}.</p>
     */
    public final void writeStringNoTag(String value) throws IOException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw MalformedProtobufException.invalidUtf8Encode(e);
        }
        byte[] utf8 = new byte[encoded.remaining()];
        encoded.get(utf8);
        writeBytesNoTag(utf8);
    }

    // ------------------------------------------------------------------ Variantes avec tag

    public final void writeInt32(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeInt32NoTag(value);
    }

    public final void writeUInt32(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeUInt32NoTag(value);
    }

    public final void writeSInt32(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeSInt32NoTag(value);
    }

    public final void writeInt64(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeInt64NoTag(value);
    }

    public final void writeUInt64(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeUInt64NoTag(value);
    }

    public final void writeSInt64(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeSInt64NoTag(value);
    }

    public final void writeFixed32(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED32);
        writeFixed32NoTag(value);
    }

    public final void writeSFixed32(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED32);
        writeSFixed32NoTag(value);
    }

    public final void writeFloat(int fieldNumber, float value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED32);
        writeFloatNoTag(value);
    }

    public final void writeFixed64(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED64);
        writeFixed64NoTag(value);
    }

    public final void writeSFixed64(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED64);
        writeSFixed64NoTag(value);
    }

    public final void writeDouble(int fieldNumber, double value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED64);
        writeDoubleNoTag(value);
    }

    public final void writeBool(int fieldNumber, boolean value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeBoolNoTag(value);
    }

    public final void writeEnum(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
        writeEnumNoTag(value);
    }

    public final void writeBytes(int fieldNumber, byte[] value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        writeBytesNoTag(value);
    }

    public final void writeString(int fieldNumber, String value) throws IOException {
        writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        writeStringNoTag(value);
    }

    // ------------------------------------------------------------------ Tailles précalculées

    /** Nombre d'octets nécessaires pour encoder {@code value} en varint. */
    public static int computeRawVarint32Size(int value) {
        if ((value & (~0 << 7)) == 0) return 1;
        if ((value & (~0 << 14)) == 0) return 2;
        if ((value & (~0 << 21)) == 0) return 3;
        if ((value & (~0 << 28)) == 0) return 4;
        return 5;
    }

    public static int computeRawVarint64Size(long value) {
        // Optimisation classique : compter par bloc de 7 bits.
        if ((value & (~0L << 7)) == 0) return 1;
        if ((value & (~0L << 14)) == 0) return 2;
        if ((value & (~0L << 21)) == 0) return 3;
        if ((value & (~0L << 28)) == 0) return 4;
        if ((value & (~0L << 35)) == 0) return 5;
        if ((value & (~0L << 42)) == 0) return 6;
        if ((value & (~0L << 49)) == 0) return 7;
        if ((value & (~0L << 56)) == 0) return 8;
        if ((value & (~0L << 63)) == 0) return 9;
        return 10;
    }

    // ------------------------------------------------------------------ API contractuelle des sous-classes

    public abstract void writeRawByte(int value) throws IOException;

    public abstract void writeRawBytes(byte[] value, int offset, int length) throws IOException;

    /**
     * Force l'écriture des octets bufférisés vers le flux sous-jacent (si applicable).
     */
    public abstract void flush() throws IOException;

    /** Position courante depuis le début du buffer (pour {@link ArrayEncoder}). */
    public abstract int getTotalBytesWritten();

    // ================================================================== Impls

    static final class ArrayEncoder extends CodedOutputStream {
        private final byte[] buffer;
        private final int offset;
        private final int limit;
        private int position;

        ArrayEncoder(byte[] buffer, int offset, int length) {
            this.buffer = buffer;
            this.offset = offset;
            this.limit = offset + length;
            this.position = offset;
        }

        @Override
        public void writeRawByte(int value) throws IOException {
            if (position >= limit) {
                throw new IOException(
                        "CodedOutputStream array buffer full (limit=" + (limit - offset) + ").");
            }
            buffer[position++] = (byte) value;
        }

        @Override
        public void writeRawBytes(byte[] value, int off, int length) throws IOException {
            if (position + length > limit) {
                throw new IOException(
                        "CodedOutputStream array buffer full (need " + length
                                + ", available " + (limit - position) + ").");
            }
            System.arraycopy(value, off, buffer, position, length);
            position += length;
        }

        @Override
        public void flush() {
            // no-op : tout est déjà dans le buffer caller-owned.
        }

        @Override
        public int getTotalBytesWritten() {
            return position - offset;
        }
    }

    static final class StreamEncoder extends CodedOutputStream {
        private final OutputStream out;
        private final byte[] buffer;
        private int position;
        private int totalBytesWritten;

        StreamEncoder(OutputStream out, int bufferSize) {
            this.out = out;
            this.buffer = new byte[bufferSize];
            this.position = 0;
            this.totalBytesWritten = 0;
        }

        @Override
        public void writeRawByte(int value) throws IOException {
            if (position == buffer.length) {
                flushBuffer();
            }
            buffer[position++] = (byte) value;
            totalBytesWritten++;
        }

        @Override
        public void writeRawBytes(byte[] value, int off, int length) throws IOException {
            if (buffer.length - position >= length) {
                System.arraycopy(value, off, buffer, position, length);
                position += length;
                totalBytesWritten += length;
                return;
            }
            // Vide le buffer puis écrit le bloc directement.
            int firstChunk = buffer.length - position;
            System.arraycopy(value, off, buffer, position, firstChunk);
            position = buffer.length;
            flushBuffer();
            int remaining = length - firstChunk;
            if (remaining >= buffer.length) {
                out.write(value, off + firstChunk, remaining);
                totalBytesWritten += remaining;
            } else {
                System.arraycopy(value, off + firstChunk, buffer, 0, remaining);
                position = remaining;
                totalBytesWritten += remaining;
            }
        }

        @Override
        public void flush() throws IOException {
            flushBuffer();
            out.flush();
        }

        @Override
        public int getTotalBytesWritten() {
            return totalBytesWritten;
        }

        private void flushBuffer() throws IOException {
            if (position > 0) {
                out.write(buffer, 0, position);
                position = 0;
            }
        }
    }
}
