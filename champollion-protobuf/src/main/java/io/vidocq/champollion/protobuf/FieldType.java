package io.vidocq.champollion.protobuf;

/**
 * Type de champ Protocol Buffers — couple {@code (wire type, encoding sémantique)}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#scalar">Proto3 §Scalar Value Types</a>.
 * Le wire type seul ne suffit pas : un {@code int} en Java peut s'encoder en
 * varint signé (INT32), unsigned (UINT32), zigzag (SINT32), little-endian fixé
 * (FIXED32 / SFIXED32). L'annotation {@code @ProtobufField(type = ...)} lève
 * cette ambiguïté.</p>
 */
public enum FieldType {

    INT32(WireFormat.WIRETYPE_VARINT, true),
    INT64(WireFormat.WIRETYPE_VARINT, true),
    UINT32(WireFormat.WIRETYPE_VARINT, true),
    UINT64(WireFormat.WIRETYPE_VARINT, true),
    SINT32(WireFormat.WIRETYPE_VARINT, true),
    SINT64(WireFormat.WIRETYPE_VARINT, true),
    BOOL(WireFormat.WIRETYPE_VARINT, true),
    ENUM(WireFormat.WIRETYPE_VARINT, true),

    FIXED32(WireFormat.WIRETYPE_FIXED32, true),
    SFIXED32(WireFormat.WIRETYPE_FIXED32, true),
    FLOAT(WireFormat.WIRETYPE_FIXED32, true),

    FIXED64(WireFormat.WIRETYPE_FIXED64, true),
    SFIXED64(WireFormat.WIRETYPE_FIXED64, true),
    DOUBLE(WireFormat.WIRETYPE_FIXED64, true),

    /** UTF-8. Non packable. */
    STRING(WireFormat.WIRETYPE_LENGTH_DELIMITED, false),
    /** Octets bruts. Non packable. */
    BYTES(WireFormat.WIRETYPE_LENGTH_DELIMITED, false),
    /** Embedded message (record annoté @ProtobufMessage). Non packable. */
    MESSAGE(WireFormat.WIRETYPE_LENGTH_DELIMITED, false);

    private final int wireType;
    private final boolean packable;

    FieldType(int wireType, boolean packable) {
        this.wireType = wireType;
        this.packable = packable;
    }

    public int wireType() {
        return wireType;
    }

    /** {@code true} si {@code repeated} peut être encodé en mode packed (proto3 par défaut). */
    public boolean packable() {
        return packable;
    }
}
