package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects wire fields whose number is ignored by the local schema.
 *
 * <p>Forward compatibility required by Edition 2023 (§Unknown Fields): a message
 * serialized by a newer version of the schema must be able to pass through an
 * older version of the code without loss. Each unknown field is stored
 * with its original number and wire type, and re-emitted as-is when
 * {@code writeTo} is called.</p>
 *
 * <p>Immutable. {@link Builder} for construction; {@link #writeTo(CodedOutputStream)}
 * re-emits fields in insertion order.</p>
 */
public final class UnknownFieldSet {

    public static final UnknownFieldSet EMPTY = new UnknownFieldSet(Map.of(), List.of());

    private final Map<Integer, Field> fields;
    /**
     * Flat list of occurrences in the exact order they were read from the wire.
     * Lets {@link #writeTo(CodedOutputStream)} re-emit while preserving
     * per-occurrence order (required by the Google conformance {@code UnknownOrdering}
     * test).
     */
    private final List<RawEntry> orderedEntries;

    private UnknownFieldSet(Map<Integer, Field> fields, List<RawEntry> orderedEntries) {
        this.fields = fields;
        this.orderedEntries = orderedEntries;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Map<Integer, Field> asMap() {
        return Collections.unmodifiableMap(fields);
    }

    public Field get(int fieldNumber) {
        return fields.get(fieldNumber);
    }

    /**
     * Re-emits all stored unknown fields. The preserved order is the
     * exact original <b>per-occurrence</b> order on the wire (cf. {@link #orderedEntries}).
     */
    public void writeTo(CodedOutputStream out) throws IOException {
        for (RawEntry e : orderedEntries) {
            e.writeTo(out);
        }
    }

    /** Size in bytes needed to re-emit unknown fields. */
    public int getSerializedSize() {
        int total = 0;
        for (RawEntry e : orderedEntries) {
            total += e.getSerializedSize();
        }
        return total;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnknownFieldSet other && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    /**
     * Field-number aggregator: all observed wire types for a
     * given number are concatenated into parallel lists (the same number
     * can appear multiple times with different wire types, e.g.
     * transition from packed → expanded between schema versions).
     */
    public static final class Field {
        private final List<Long> varints;
        private final List<Integer> fixed32s;
        private final List<Long> fixed64s;
        private final List<byte[]> lengthDelimited;

        private Field(List<Long> varints,
                      List<Integer> fixed32s,
                      List<Long> fixed64s,
                      List<byte[]> lengthDelimited) {
            this.varints = varints;
            this.fixed32s = fixed32s;
            this.fixed64s = fixed64s;
            this.lengthDelimited = lengthDelimited;
        }

        public List<Long> varints() { return varints; }
        public List<Integer> fixed32s() { return fixed32s; }
        public List<Long> fixed64s() { return fixed64s; }
        public List<byte[]> lengthDelimited() { return lengthDelimited; }

        void writeTo(int fieldNumber, CodedOutputStream out) throws IOException {
            for (long v : varints) {
                out.writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
                out.writeRawVarint64(v);
            }
            for (int v : fixed32s) {
                out.writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED32);
                out.writeRawLittleEndian32(v);
            }
            for (long v : fixed64s) {
                out.writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED64);
                out.writeRawLittleEndian64(v);
            }
            for (byte[] v : lengthDelimited) {
                out.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                out.writeRawVarint32(v.length);
                out.writeRawBytes(v, 0, v.length);
            }
        }

        int getSerializedSize(int fieldNumber) {
            int total = 0;
            int tagSize = CodedOutputStream.computeRawVarint32Size(WireFormat.makeTag(fieldNumber, 0));
            for (long v : varints) {
                total += tagSize + CodedOutputStream.computeRawVarint64Size(v);
            }
            total += (tagSize + 4) * fixed32s.size();
            total += (tagSize + 8) * fixed64s.size();
            for (byte[] v : lengthDelimited) {
                total += tagSize + CodedOutputStream.computeRawVarint32Size(v.length) + v.length;
            }
            return total;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Field other)) return false;
            return varints.equals(other.varints)
                    && fixed32s.equals(other.fixed32s)
                    && fixed64s.equals(other.fixed64s)
                    && byteListsEqual(lengthDelimited, other.lengthDelimited);
        }

        @Override
        public int hashCode() {
            int h = varints.hashCode();
            h = 31 * h + fixed32s.hashCode();
            h = 31 * h + fixed64s.hashCode();
            for (byte[] b : lengthDelimited) h = 31 * h + java.util.Arrays.hashCode(b);
            return h;
        }

        private static boolean byteListsEqual(List<byte[]> a, List<byte[]> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
            }
            return true;
        }
    }

    /**
     * Builds an {@link UnknownFieldSet}. Implements the
     * {@link CodedInputStream.UnknownFieldRecorder} interface to plug directly
     * into {@code skipField(tag, recorder)}.
     */
    public static final class Builder implements CodedInputStream.UnknownFieldRecorder {
        private final Map<Integer, FieldBuilder> fields = new LinkedHashMap<>();
        private final List<RawEntry> entries = new ArrayList<>();

        @Override
        public void recordVarint(int fieldNumber, long value) {
            fieldFor(fieldNumber).varints.add(value);
            entries.add(RawEntry.varint(fieldNumber, value));
        }

        @Override
        public void recordFixed64(int fieldNumber, long value) {
            fieldFor(fieldNumber).fixed64s.add(value);
            entries.add(RawEntry.fixed64(fieldNumber, value));
        }

        @Override
        public void recordLengthDelimited(int fieldNumber, byte[] value) {
            fieldFor(fieldNumber).lengthDelimited.add(value);
            entries.add(RawEntry.lengthDelimited(fieldNumber, value));
        }

        @Override
        public void recordFixed32(int fieldNumber, int value) {
            fieldFor(fieldNumber).fixed32s.add(value);
            entries.add(RawEntry.fixed32(fieldNumber, value));
        }

        private FieldBuilder fieldFor(int fieldNumber) {
            return fields.computeIfAbsent(fieldNumber, n -> new FieldBuilder());
        }

        public UnknownFieldSet build() {
            if (fields.isEmpty()) return EMPTY;
            Map<Integer, Field> snapshot = new LinkedHashMap<>(fields.size());
            for (Map.Entry<Integer, FieldBuilder> e : fields.entrySet()) {
                snapshot.put(e.getKey(), e.getValue().toField());
            }
            return new UnknownFieldSet(
                    Collections.unmodifiableMap(snapshot),
                    List.copyOf(entries));
        }
    }

    /** A single wire occurrence of an unknown field, in arrival order. */
    private record RawEntry(int fieldNumber, int wireType,
                            long varintOrFixed64, int fixed32,
                            byte[] lengthDelimited) {

        static RawEntry varint(int fn, long v) {
            return new RawEntry(fn, WireFormat.WIRETYPE_VARINT, v, 0, null);
        }

        static RawEntry fixed64(int fn, long v) {
            return new RawEntry(fn, WireFormat.WIRETYPE_FIXED64, v, 0, null);
        }

        static RawEntry fixed32(int fn, int v) {
            return new RawEntry(fn, WireFormat.WIRETYPE_FIXED32, 0L, v, null);
        }

        static RawEntry lengthDelimited(int fn, byte[] v) {
            return new RawEntry(fn, WireFormat.WIRETYPE_LENGTH_DELIMITED, 0L, 0, v);
        }

        void writeTo(CodedOutputStream out) throws IOException {
            out.writeTag(fieldNumber, wireType);
            switch (wireType) {
                case WireFormat.WIRETYPE_VARINT -> out.writeRawVarint64(varintOrFixed64);
                case WireFormat.WIRETYPE_FIXED64 -> out.writeRawLittleEndian64(varintOrFixed64);
                case WireFormat.WIRETYPE_FIXED32 -> out.writeRawLittleEndian32(fixed32);
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    out.writeRawVarint32(lengthDelimited.length);
                    out.writeRawBytes(lengthDelimited, 0, lengthDelimited.length);
                }
                default -> throw new IOException("Invalid wire type for unknown field: " + wireType);
            }
        }

        int getSerializedSize() {
            int tagSize = CodedOutputStream.computeRawVarint32Size(
                    WireFormat.makeTag(fieldNumber, wireType));
            return switch (wireType) {
                case WireFormat.WIRETYPE_VARINT ->
                        tagSize + CodedOutputStream.computeRawVarint64Size(varintOrFixed64);
                case WireFormat.WIRETYPE_FIXED64 -> tagSize + 8;
                case WireFormat.WIRETYPE_FIXED32 -> tagSize + 4;
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> tagSize
                        + CodedOutputStream.computeRawVarint32Size(lengthDelimited.length)
                        + lengthDelimited.length;
                default -> 0;
            };
        }
    }

    private static final class FieldBuilder {
        final List<Long> varints = new ArrayList<>();
        final List<Integer> fixed32s = new ArrayList<>();
        final List<Long> fixed64s = new ArrayList<>();
        final List<byte[]> lengthDelimited = new ArrayList<>();

        Field toField() {
            return new Field(
                    List.copyOf(varints),
                    List.copyOf(fixed32s),
                    List.copyOf(fixed64s),
                    List.copyOf(lengthDelimited));
        }
    }
}
