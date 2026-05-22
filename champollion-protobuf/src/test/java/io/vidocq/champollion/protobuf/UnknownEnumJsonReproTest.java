package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnknownEnumJsonReproTest {

    public enum E { A, B }

    @ProtobufMessage("ReproEnum")
    public record ReproEnum(
            @ProtobufField(number = 21, type = FieldType.ENUM) E e,
            UnknownFieldSet unknownFields) {}

    @Test
    void unknown_enum_int_preserved_in_json_output() {
        ReproEnum r = ProtobufJson.fromJson(ReproEnum.class, "{\"e\": 123}");
        System.out.println("unknownFields=" + r.unknownFields().asMap());
        System.out.println("e=" + r.e());
        // Construire manuellement pour valider le path WRITE
        UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
        b.recordVarint(21, 123);
        ReproEnum manual = new ReproEnum(null, b.build());
        System.out.println("manual.unknownFields=" + manual.unknownFields().asMap());
        String json = ProtobufJson.toJson(manual);
        System.out.println("OUT-manual: " + json);
        assertTrue(json.contains("\"e\":123") || json.contains("\"e\": 123"), json);
    }
}
