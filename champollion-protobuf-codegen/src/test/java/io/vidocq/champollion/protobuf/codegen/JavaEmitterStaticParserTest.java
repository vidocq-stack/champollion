package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests M3.2 — flag {@code staticParser} de {@link JavaEmitter} : ajoute
 * {@code @ProtobufStatic} sur chaque record émis pour que l'APT M3.1 produise
 * un parser zéro-réflexion.
 */
@DisplayName("JavaEmitter — staticParser flag")
class JavaEmitterStaticParserTest {

    private Descriptors.FileDescriptor descriptor() {
        return SchemaResolver.resolve(ProtoParser.parse("p.proto", """
                syntax = "proto3";
                package app;
                message Item { string name = 1; }
                """));
    }

    @Test
    void without_staticParser_no_annotation() {
        Map<String, String> out = new JavaEmitter("io.gen", false).emit(descriptor());
        String src = out.get("io.gen.Item");
        assertTrue(src.contains("@ProtobufMessage("), src);
        assertFalse(src.contains("@ProtobufStatic"), "static annotation should not be present: " + src);
        assertFalse(src.contains("import io.vidocq.champollion.protobuf.ProtobufStatic"),
                "static import should not be present: " + src);
    }

    @Test
    void with_staticParser_annotation_and_import_present() {
        Map<String, String> out = new JavaEmitter("io.gen", true).emit(descriptor());
        String src = out.get("io.gen.Item");
        assertTrue(src.contains("@ProtobufStatic"), src);
        assertTrue(src.contains("@ProtobufMessage("), src);
        assertTrue(src.contains("import io.vidocq.champollion.protobuf.ProtobufStatic"), src);
        // L'ordre attendu : @ProtobufStatic avant @ProtobufMessage avant le record.
        int sIdx = src.indexOf("@ProtobufStatic");
        int mIdx = src.indexOf("@ProtobufMessage(");
        int rIdx = src.indexOf("public record ");
        assertTrue(sIdx < mIdx && mIdx < rIdx,
                "order @ProtobufStatic < @ProtobufMessage < record : " + src);
    }

    @Test
    void single_arg_constructor_defaults_to_false() {
        Map<String, String> out = new JavaEmitter("io.gen").emit(descriptor());
        String src = out.get("io.gen.Item");
        assertFalse(src.contains("@ProtobufStatic"), src);
    }
}
