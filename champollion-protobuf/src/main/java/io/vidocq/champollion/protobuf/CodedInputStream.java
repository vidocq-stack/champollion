package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Décode des primitives Protocol Buffers depuis un {@code byte[]} ou un {@link InputStream}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 *
 * <p>Pull parser tag-par-tag : {@link #readTag()} renvoie 0 quand l'entrée est
 * épuisée. L'appelant boucle, dispatche selon {@link WireFormat#getTagWireType(int)},
 * et appelle {@link #skipField(int)} pour les champs inconnus (forward-compat
 * exigée par Edition 2023).</p>
 */
public abstract sealed class CodedInputStream permits CodedInputStream.ArrayDecoder,
                                                      CodedInputStream.StreamDecoder {

    /** Limite par défaut de récursion d'embedded messages — protection anti-stack-overflow. */
    public static final int DEFAULT_RECURSION_LIMIT = 100;

    int recursionDepth = 0;
    int recursionLimit = DEFAULT_RECURSION_LIMIT;
    int lastTag = 0;

    public static CodedInputStream newInstance(byte[] data) {
        Objects.requireNonNull(data, "data");
        return new ArrayDecoder(data, 0, data.length);
    }

    public static CodedInputStream newInstance(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "data.length=" + data.length + ", offset=" + offset + ", length=" + length);
        }
        return new ArrayDecoder(data, offset, length);
    }

    public static CodedInputStream newInstance(InputStream in) {
        Objects.requireNonNull(in, "in");
        return new StreamDecoder(in, 4096);
    }

    public static CodedInputStream newInstance(InputStream in, int bufferSize) {
        Objects.requireNonNull(in, "in");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be > 0");
        }
        return new StreamDecoder(in, bufferSize);
    }

    // ------------------------------------------------------------------ Configuration

    public int setRecursionLimit(int newLimit) {
        if (newLimit < 0) {
            throw new IllegalArgumentException("recursionLimit must be >= 0");
        }
        int old = recursionLimit;
        recursionLimit = newLimit;
        return old;
    }

    // ------------------------------------------------------------------ Tag

    /**
     * Lit le prochain tag du flux. Retourne 0 si le flux est épuisé (fin
     * normale de message). Retourne aussi 0 si le wire type lu est END_GROUP
     * (le caller saura distinguer via le contexte du parser de groupe).
     */
    public final int readTag() throws IOException {
        if (isAtEnd()) {
            lastTag = 0;
            return 0;
        }
        int tag = readRawVarint32();
        if (WireFormat.getTagFieldNumber(tag) == 0) {
            throw new MalformedProtobufException(
                    "Protocol message contained an invalid tag (zero field number).");
        }
        lastTag = tag;
        return tag;
    }

    /**
     * Vérifie que le dernier tag lu correspond bien à la valeur attendue.
     * Utile à la fin d'un parsing de groupe (END_GROUP attendu).
     */
    public final void checkLastTagWas(int expected) throws MalformedProtobufException {
        if (lastTag != expected) {
            throw new MalformedProtobufException(
                    "Protocol message end-group tag did not match expected tag.");
        }
    }

    // ------------------------------------------------------------------ Varints

    public abstract int readRawVarint32() throws IOException;

    public abstract long readRawVarint64() throws IOException;

    public abstract int readRawLittleEndian32() throws IOException;

    public abstract long readRawLittleEndian64() throws IOException;

    public abstract byte readRawByte() throws IOException;

    public abstract byte[] readRawBytes(int size) throws IOException;

    public abstract void skipRawBytes(int size) throws IOException;

    /** {@code true} si le flux n'a plus d'octets disponibles. */
    public abstract boolean isAtEnd() throws IOException;

    // ------------------------------------------------------------------ Zigzag

    /** {@code (n >>> 1) ^ -(n & 1)} — spec §1 ZigZag Encoding. */
    public static int decodeZigZag32(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    public static long decodeZigZag64(long n) {
        return (n >>> 1) ^ -(n & 1L);
    }

    // ------------------------------------------------------------------ Scalaires typés

    public final int readInt32() throws IOException {
        return (int) readRawVarint64();
    }

    public final int readUInt32() throws IOException {
        return readRawVarint32();
    }

    public final int readSInt32() throws IOException {
        return decodeZigZag32(readRawVarint32());
    }

    public final long readInt64() throws IOException {
        return readRawVarint64();
    }

    public final long readUInt64() throws IOException {
        return readRawVarint64();
    }

    public final long readSInt64() throws IOException {
        return decodeZigZag64(readRawVarint64());
    }

    public final int readFixed32() throws IOException {
        return readRawLittleEndian32();
    }

    public final int readSFixed32() throws IOException {
        return readRawLittleEndian32();
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readRawLittleEndian32());
    }

    public final long readFixed64() throws IOException {
        return readRawLittleEndian64();
    }

    public final long readSFixed64() throws IOException {
        return readRawLittleEndian64();
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readRawLittleEndian64());
    }

    public final boolean readBool() throws IOException {
        return readRawVarint64() != 0L;
    }

    public final int readEnum() throws IOException {
        return readInt32();
    }

    public final byte[] readBytes() throws IOException {
        int size = readRawVarint32();
        if (size < 0) {
            throw MalformedProtobufException.negativeSize();
        }
        return readRawBytes(size);
    }

    public final String readString() throws IOException {
        int size = readRawVarint32();
        if (size < 0) {
            throw MalformedProtobufException.negativeSize();
        }
        byte[] bytes = readRawBytes(size);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ Skip

    /**
     * Skip le champ correspondant au {@code tag} déjà lu. Stocke l'octet de tag
     * et le payload comme « unknown field » si {@code unknownFields} est non null
     * (forward-compat exigée par Edition 2023 §"Unknown Fields").
     */
    public final boolean skipField(int tag) throws IOException {
        return skipField(tag, null);
    }

    public final boolean skipField(int tag, UnknownFieldRecorder unknownFields) throws IOException {
        int wireType = WireFormat.getTagWireType(tag);
        switch (wireType) {
            case WireFormat.WIRETYPE_VARINT -> {
                long value = readRawVarint64();
                if (unknownFields != null) unknownFields.recordVarint(WireFormat.getTagFieldNumber(tag), value);
                return true;
            }
            case WireFormat.WIRETYPE_FIXED64 -> {
                long value = readRawLittleEndian64();
                if (unknownFields != null) unknownFields.recordFixed64(WireFormat.getTagFieldNumber(tag), value);
                return true;
            }
            case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                int size = readRawVarint32();
                if (size < 0) throw MalformedProtobufException.negativeSize();
                byte[] bytes = readRawBytes(size);
                if (unknownFields != null) unknownFields.recordLengthDelimited(WireFormat.getTagFieldNumber(tag), bytes);
                return true;
            }
            case WireFormat.WIRETYPE_FIXED32 -> {
                int value = readRawLittleEndian32();
                if (unknownFields != null) unknownFields.recordFixed32(WireFormat.getTagFieldNumber(tag), value);
                return true;
            }
            case WireFormat.WIRETYPE_START_GROUP -> {
                // Skip jusqu'au END_GROUP correspondant.
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                while (true) {
                    int nextTag = readTag();
                    if (nextTag == 0) {
                        throw new MalformedProtobufException(
                                "Protocol message group is truncated.");
                    }
                    if (WireFormat.getTagWireType(nextTag) == WireFormat.WIRETYPE_END_GROUP) {
                        if (WireFormat.getTagFieldNumber(nextTag) != fieldNumber) {
                            throw new MalformedProtobufException(
                                    "Protocol message END_GROUP tag did not match SGROUP tag.");
                        }
                        return true;
                    }
                    skipField(nextTag, unknownFields);
                }
            }
            case WireFormat.WIRETYPE_END_GROUP -> {
                // Le caller doit gérer END_GROUP via readTag()/checkLastTagWas().
                return false;
            }
            default -> throw MalformedProtobufException.invalidWireType(wireType);
        }
    }

    // ------------------------------------------------------------------ Recursion limit (embedded messages)

    /**
     * Incrémente la profondeur de récursion ; lance {@link MalformedProtobufException}
     * si la limite est dépassée. À appeler avant tout {@code readMessage}.
     */
    public final void incrementRecursionDepth() throws MalformedProtobufException {
        if (++recursionDepth > recursionLimit) {
            throw MalformedProtobufException.recursionLimitExceeded();
        }
    }

    public final void decrementRecursionDepth() {
        --recursionDepth;
    }

    // ================================================================== Impls

    static final class ArrayDecoder extends CodedInputStream {
        private final byte[] buffer;
        private final int limit;
        private int position;

        ArrayDecoder(byte[] buffer, int offset, int length) {
            this.buffer = buffer;
            this.limit = offset + length;
            this.position = offset;
        }

        @Override
        public boolean isAtEnd() {
            return position >= limit;
        }

        @Override
        public byte readRawByte() throws IOException {
            if (position >= limit) throw MalformedProtobufException.truncated();
            return buffer[position++];
        }

        @Override
        public byte[] readRawBytes(int size) throws IOException {
            if (size < 0) throw MalformedProtobufException.negativeSize();
            if (size == 0) return EMPTY_BYTES;
            if (limit - position < size) throw MalformedProtobufException.truncated();
            byte[] out = new byte[size];
            System.arraycopy(buffer, position, out, 0, size);
            position += size;
            return out;
        }

        @Override
        public void skipRawBytes(int size) throws IOException {
            if (size < 0) throw MalformedProtobufException.negativeSize();
            if (limit - position < size) throw MalformedProtobufException.truncated();
            position += size;
        }

        @Override
        public int readRawVarint32() throws IOException {
            // Fast path : un octet, MSB à 0.
            int p = position;
            if (limit == p) throw MalformedProtobufException.truncated();
            int x = buffer[p++];
            if (x >= 0) {
                position = p;
                return x;
            } else if (limit - p < 9) {
                position = p - 1;
                return (int) slowReadRawVarint64();
            }
            // Slow path déroulé sur 5 octets max pour un int32 varint.
            int y;
            if ((y = buffer[p++]) >= 0) {
                x ^= (y << 7);
                x ^= (~0 << 7);
            } else {
                x ^= (y << 7);
                if ((y = buffer[p++]) >= 0) {
                    x ^= (y << 14);
                    x ^= (~0 << 7) ^ (~0 << 14);
                } else {
                    x ^= (y << 14);
                    if ((y = buffer[p++]) >= 0) {
                        x ^= (y << 21);
                        x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
                    } else {
                        x ^= (y << 21);
                        y = buffer[p++];
                        x ^= (y << 28);
                        x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                        if (y < 0
                                && buffer[p++] < 0
                                && buffer[p++] < 0
                                && buffer[p++] < 0
                                && buffer[p++] < 0
                                && buffer[p++] < 0) {
                            // Drop 6 octets de sign-extension : int32 négatif passé en 10 octets.
                            throw MalformedProtobufException.malformedVarint();
                        }
                    }
                }
            }
            position = p;
            return x;
        }

        @Override
        public long readRawVarint64() throws IOException {
            return slowReadRawVarint64();
        }

        private long slowReadRawVarint64() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= limit) throw MalformedProtobufException.truncated();
                byte b = buffer[position++];
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw MalformedProtobufException.malformedVarint();
        }

        @Override
        public int readRawLittleEndian32() throws IOException {
            if (limit - position < 4) throw MalformedProtobufException.truncated();
            int b1 = buffer[position++] & 0xFF;
            int b2 = buffer[position++] & 0xFF;
            int b3 = buffer[position++] & 0xFF;
            int b4 = buffer[position++] & 0xFF;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
        }

        @Override
        public long readRawLittleEndian64() throws IOException {
            if (limit - position < 8) throw MalformedProtobufException.truncated();
            long b1 = buffer[position++] & 0xFFL;
            long b2 = buffer[position++] & 0xFFL;
            long b3 = buffer[position++] & 0xFFL;
            long b4 = buffer[position++] & 0xFFL;
            long b5 = buffer[position++] & 0xFFL;
            long b6 = buffer[position++] & 0xFFL;
            long b7 = buffer[position++] & 0xFFL;
            long b8 = buffer[position++] & 0xFFL;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24)
                    | (b5 << 32) | (b6 << 40) | (b7 << 48) | (b8 << 56);
        }
    }

    static final class StreamDecoder extends CodedInputStream {
        private final InputStream in;
        private final byte[] buffer;
        private int bufferPos;
        private int bufferSize;
        private boolean eof;

        StreamDecoder(InputStream in, int bufferSize) {
            this.in = in;
            this.buffer = new byte[bufferSize];
            this.bufferPos = 0;
            this.bufferSize = 0;
        }

        private boolean refill() throws IOException {
            if (eof) return false;
            int read = in.read(buffer, 0, buffer.length);
            if (read <= 0) {
                eof = true;
                bufferPos = 0;
                bufferSize = 0;
                return false;
            }
            bufferPos = 0;
            bufferSize = read;
            return true;
        }

        @Override
        public boolean isAtEnd() throws IOException {
            return bufferPos == bufferSize && !refill();
        }

        @Override
        public byte readRawByte() throws IOException {
            if (bufferPos == bufferSize && !refill()) {
                throw MalformedProtobufException.truncated();
            }
            return buffer[bufferPos++];
        }

        @Override
        public byte[] readRawBytes(int size) throws IOException {
            if (size < 0) throw MalformedProtobufException.negativeSize();
            if (size == 0) return EMPTY_BYTES;
            byte[] out = new byte[size];
            int filled = 0;
            while (filled < size) {
                int available = bufferSize - bufferPos;
                if (available == 0) {
                    if (!refill()) throw MalformedProtobufException.truncated();
                    continue;
                }
                int chunk = Math.min(available, size - filled);
                System.arraycopy(buffer, bufferPos, out, filled, chunk);
                bufferPos += chunk;
                filled += chunk;
            }
            return out;
        }

        @Override
        public void skipRawBytes(int size) throws IOException {
            if (size < 0) throw MalformedProtobufException.negativeSize();
            int remaining = size;
            while (remaining > 0) {
                int available = bufferSize - bufferPos;
                if (available == 0) {
                    if (!refill()) throw MalformedProtobufException.truncated();
                    continue;
                }
                int chunk = Math.min(available, remaining);
                bufferPos += chunk;
                remaining -= chunk;
            }
        }

        @Override
        public int readRawVarint32() throws IOException {
            return (int) readRawVarint64();
        }

        @Override
        public long readRawVarint64() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte b = readRawByte();
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw MalformedProtobufException.malformedVarint();
        }

        @Override
        public int readRawLittleEndian32() throws IOException {
            int b1 = readRawByte() & 0xFF;
            int b2 = readRawByte() & 0xFF;
            int b3 = readRawByte() & 0xFF;
            int b4 = readRawByte() & 0xFF;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
        }

        @Override
        public long readRawLittleEndian64() throws IOException {
            long b1 = readRawByte() & 0xFFL;
            long b2 = readRawByte() & 0xFFL;
            long b3 = readRawByte() & 0xFFL;
            long b4 = readRawByte() & 0xFFL;
            long b5 = readRawByte() & 0xFFL;
            long b6 = readRawByte() & 0xFFL;
            long b7 = readRawByte() & 0xFFL;
            long b8 = readRawByte() & 0xFFL;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24)
                    | (b5 << 32) | (b6 << 40) | (b7 << 48) | (b8 << 56);
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * SPI optionnelle pour collecter les champs inconnus lors d'un skip
     * (forward-compat Edition 2023). Le runtime fournit un recorder via le
     * binding ; la SPI est exposée ici pour découpler le décodeur du binding.
     */
    public interface UnknownFieldRecorder {
        void recordVarint(int fieldNumber, long value);
        void recordFixed64(int fieldNumber, long value);
        void recordLengthDelimited(int fieldNumber, byte[] value);
        void recordFixed32(int fieldNumber, int value);
    }
}
