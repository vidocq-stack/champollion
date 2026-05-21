package io.vidocq.champollion.protobuf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collecte les champs lus du wire dont le schéma local ignore le numéro.
 *
 * <p>Forward-compat exigée par Edition 2023 (§Unknown Fields) : un message
 * sérialisé par une version récente du schéma doit pouvoir transiter par une
 * version plus ancienne du code sans perte. Chaque champ inconnu est stocké
 * avec son numéro et son wire type d'origine, et ré-émis tel quel au moment
 * du {@code writeTo}.</p>
 *
 * <p>Immutable. {@link Builder} pour la construction ; {@link #writeTo(CodedOutputStream)}
 * réémet les champs dans l'ordre d'insertion.</p>
 */
public final class UnknownFieldSet {

    public static final UnknownFieldSet EMPTY = new UnknownFieldSet(Map.of());

    private final Map<Integer, Field> fields;

    private UnknownFieldSet(Map<Integer, Field> fields) {
        this.fields = fields;
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
     * Réémet tous les champs inconnus stockés. L'ordre préservé est celui de
     * la première insertion par numéro de champ (LinkedHashMap).
     */
    public void writeTo(CodedOutputStream out) throws IOException {
        for (Map.Entry<Integer, Field> entry : fields.entrySet()) {
            entry.getValue().writeTo(entry.getKey(), out);
        }
    }

    /** Taille en octets nécessaire pour réémettre les champs inconnus. */
    public int getSerializedSize() {
        int total = 0;
        for (Map.Entry<Integer, Field> entry : fields.entrySet()) {
            total += entry.getValue().getSerializedSize(entry.getKey());
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
     * Aggrégateur par numéro de champ : tous les wire types observés pour un
     * même numéro sont concaténés dans des listes parallèles (un même numéro
     * peut apparaître plusieurs fois avec des wire types différents, ex.
     * passage packed → expanded entre versions du schéma).
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
     * Construction d'un {@link UnknownFieldSet}. Implémente l'interface
     * {@link CodedInputStream.UnknownFieldRecorder} pour être branchée
     * directement sur {@code skipField(tag, recorder)}.
     */
    public static final class Builder implements CodedInputStream.UnknownFieldRecorder {
        private final Map<Integer, FieldBuilder> fields = new LinkedHashMap<>();

        @Override
        public void recordVarint(int fieldNumber, long value) {
            fieldFor(fieldNumber).varints.add(value);
        }

        @Override
        public void recordFixed64(int fieldNumber, long value) {
            fieldFor(fieldNumber).fixed64s.add(value);
        }

        @Override
        public void recordLengthDelimited(int fieldNumber, byte[] value) {
            fieldFor(fieldNumber).lengthDelimited.add(value);
        }

        @Override
        public void recordFixed32(int fieldNumber, int value) {
            fieldFor(fieldNumber).fixed32s.add(value);
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
            return new UnknownFieldSet(Collections.unmodifiableMap(snapshot));
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
