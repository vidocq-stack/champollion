package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SchemaResolver#resolveAll} — résolution multi-fichier avec
 * références cross-file.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/#identifiers">Proto3 §Type Lookup</a>.</p>
 */
class MultiFileResolverTest {

    private ProtoFile parse(String name, String src) {
        return ProtoParser.parse(name, src);
    }

    @Nested
    @DisplayName("Cross-file lookup")
    class Cross {

        @Test
        void message_in_file_a_references_type_in_file_b() {
            ProtoFile b = parse("b.proto", """
                    syntax = "proto3";
                    package shared;
                    message Address { string city = 1; }
                    """);
            ProtoFile a = parse("a.proto", """
                    syntax = "proto3";
                    package app;
                    import "b.proto";
                    message Person {
                      string name = 1;
                      shared.Address address = 2;
                    }
                    """);
            Map<String, Descriptors.FileDescriptor> all = SchemaResolver.resolveAll(List.of(a, b));
            Descriptors.FileDescriptor fileA = all.get("a.proto");
            Descriptors.FieldDescriptor addr = fileA.findMessageType("Person").findFieldByNumber(2);
            assertEquals(FieldType.MESSAGE, addr.type());
            assertEquals("shared.Address", addr.messageTypeName());
        }

        @Test
        void enum_cross_file() {
            ProtoFile e = parse("enums.proto", """
                    syntax = "proto3";
                    package ext;
                    enum Color { RED = 0; BLUE = 1; }
                    """);
            ProtoFile m = parse("main.proto", """
                    syntax = "proto3";
                    package app;
                    import "enums.proto";
                    message Item { ext.Color c = 1; }
                    """);
            var all = SchemaResolver.resolveAll(List.of(m, e));
            Descriptors.FieldDescriptor f = all.get("main.proto").findMessageType("Item").findFieldByNumber(1);
            assertEquals(FieldType.ENUM, f.type());
            assertEquals("ext.Color", f.enumTypeName());
        }

        @Test
        void fully_qualified_with_leading_dot_cross_file() {
            ProtoFile b = parse("b.proto", """
                    syntax = "proto3";
                    package one.two;
                    message Foo { int32 i = 1; }
                    """);
            ProtoFile a = parse("a.proto", """
                    syntax = "proto3";
                    package other;
                    import "b.proto";
                    message Bar { .one.two.Foo foo = 1; }
                    """);
            var all = SchemaResolver.resolveAll(List.of(a, b));
            Descriptors.FieldDescriptor f = all.get("a.proto").findMessageType("Bar").findFieldByNumber(1);
            assertEquals("one.two.Foo", f.messageTypeName());
        }

        @Test
        void order_of_input_preserved_in_output_map() {
            ProtoFile a = parse("a.proto", "syntax = \"proto3\"; message A { string s = 1; }");
            ProtoFile b = parse("b.proto", "syntax = \"proto3\"; message B { string s = 1; }");
            ProtoFile c = parse("c.proto", "syntax = \"proto3\"; message C { string s = 1; }");
            var all = SchemaResolver.resolveAll(List.of(c, a, b));
            assertEquals(List.of("c.proto", "a.proto", "b.proto"), List.copyOf(all.keySet()));
        }
    }

    @Nested
    @DisplayName("Erreurs résolution")
    class Errors {

        @Test
        void unresolved_cross_file_still_throws() {
            ProtoFile a = parse("a.proto", """
                    syntax = "proto3";
                    message Bar { Unknown u = 1; }
                    """);
            assertThrows(SchemaResolver.SchemaResolutionException.class,
                    () -> SchemaResolver.resolveAll(List.of(a)));
        }

        @Test
        void duplicate_fullName_across_files_rejected() {
            ProtoFile a = parse("a.proto", """
                    syntax = "proto3";
                    package x;
                    message Foo { int32 i = 1; }
                    """);
            ProtoFile b = parse("b.proto", """
                    syntax = "proto3";
                    package x;
                    message Foo { string s = 1; }
                    """);
            assertThrows(SchemaResolver.SchemaResolutionException.class,
                    () -> SchemaResolver.resolveAll(List.of(a, b)));
        }
    }

    @Nested
    @DisplayName("Single-file resolve == resolveAll(singleton)")
    class SingleEquivalence {

        @Test
        void identical_descriptors() {
            ProtoFile f = parse("p.proto", """
                    syntax = "proto3";
                    package x;
                    message M { string s = 1; int32 i = 2; }
                    """);
            Descriptors.FileDescriptor solo = SchemaResolver.resolve(f);
            Descriptors.FileDescriptor multi = SchemaResolver.resolveAll(List.of(f)).get("p.proto");
            assertEquals(solo.name(), multi.name());
            assertEquals(solo.packageName(), multi.packageName());
            assertEquals(solo.messageTypes().size(), multi.messageTypes().size());
            assertEquals(solo.messageTypes().get(0).fullName(),
                    multi.messageTypes().get(0).fullName());
            assertEquals(solo.messageTypes().get(0).fields().size(),
                    multi.messageTypes().get(0).fields().size());
        }
    }
}
