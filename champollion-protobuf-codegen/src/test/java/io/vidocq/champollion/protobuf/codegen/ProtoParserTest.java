package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldKind;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MessageDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.NamedType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Scalar;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ScalarType;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ProtoParser}.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>.</p>
 */
class ProtoParserTest {

    private ProtoFile parse(String src) {
        return ProtoParser.parse("inline.proto", src);
    }

    @Nested
    @DisplayName("Syntax — proto2/proto3/editions")
    class SyntaxDecl {

        @Test
        void proto3_recognized() {
            ProtoFile f = parse("syntax = \"proto3\"; package foo;");
            assertInstanceOf(ProtoAst.Proto3.class, f.syntax());
            assertEquals("foo", f.packageName());
        }

        @Test
        void proto2_recognized() {
            ProtoFile f = parse("syntax = \"proto2\";");
            assertInstanceOf(ProtoAst.Proto2.class, f.syntax());
        }

        @Test
        void edition_recognized() {
            ProtoFile f = parse("edition = \"2023\"; package x.y;");
            assertInstanceOf(ProtoAst.Edition.class, f.syntax());
            assertEquals("2023", ((ProtoAst.Edition) f.syntax()).name());
            assertEquals("x.y", f.packageName());
        }

        @Test
        void unknown_syntax_throws() {
            assertThrows(ProtoSyntaxException.class,
                    () -> parse("syntax = \"proto4\";"));
        }

        @Test
        void default_to_proto2_if_missing() {
            ProtoFile f = parse("package x;");
            assertInstanceOf(ProtoAst.Proto2.class, f.syntax());
        }
    }

    @Nested
    @DisplayName("Imports — public/weak")
    class Imports {

        @Test
        void simple_import() {
            ProtoFile f = parse("syntax = \"proto3\"; import \"other.proto\";");
            assertEquals(1, f.imports().size());
            assertEquals("other.proto", f.imports().get(0).path());
            assertEquals(false, f.imports().get(0).publicImport());
        }

        @Test
        void public_import() {
            ProtoFile f = parse("syntax = \"proto3\"; import public \"a.proto\";");
            assertTrue(f.imports().get(0).publicImport());
        }

        @Test
        void weak_import() {
            ProtoFile f = parse("syntax = \"proto3\"; import weak \"b.proto\";");
            assertTrue(f.imports().get(0).weakImport());
        }
    }

    @Nested
    @DisplayName("Message — fields + types scalaires + repeated")
    class Messages {

        @Test
        void person_with_scalars_and_repeated() {
            String src = """
                    syntax = "proto3";
                    package example;
                    message Person {
                      string name = 1;
                      int32 age = 2;
                      repeated string tags = 3;
                      bool active = 4;
                    }
                    """;
            ProtoFile f = parse(src);
            assertEquals(1, f.messages().size());
            MessageDecl m = f.messages().get(0);
            assertEquals("Person", m.name());
            assertEquals(4, m.fields().size());

            assertEquals("name", m.fields().get(0).name());
            assertEquals(1, m.fields().get(0).number());
            assertEquals(FieldKind.SINGULAR, m.fields().get(0).kind());
            assertEquals(Scalar.STRING, ((ScalarType) m.fields().get(0).type()).scalar());

            assertEquals(FieldKind.REPEATED, m.fields().get(2).kind());
        }

        @Test
        void nested_message() {
            String src = """
                    syntax = "proto3";
                    message Outer {
                      message Inner { int32 x = 1; }
                      Inner inner = 1;
                    }
                    """;
            ProtoFile f = parse(src);
            MessageDecl outer = f.messages().get(0);
            assertEquals(1, outer.nestedMessages().size());
            assertEquals("Inner", outer.nestedMessages().get(0).name());
            assertInstanceOf(NamedType.class, outer.fields().get(0).type());
            assertEquals("Inner", ((NamedType) outer.fields().get(0).type()).fullName());
        }

        @Test
        void qualified_named_type() {
            String src = """
                    syntax = "proto3";
                    message Holder {
                      google.protobuf.Timestamp t = 1;
                    }
                    """;
            ProtoFile f = parse(src);
            NamedType nt = (NamedType) f.messages().get(0).fields().get(0).type();
            assertEquals("google.protobuf.Timestamp", nt.fullName());
        }

        @Test
        void inline_field_options_skipped() {
            String src = """
                    syntax = "proto3";
                    message M {
                      repeated int32 nums = 1 [packed = true];
                    }
                    """;
            ProtoFile f = parse(src);
            assertEquals(1, f.messages().get(0).fields().size());
        }
    }

    @Nested
    @DisplayName("Enum")
    class Enums {

        @Test
        void simple_enum() {
            String src = """
                    syntax = "proto3";
                    enum Role { USER = 0; ADMIN = 1; SUPER = 2; }
                    """;
            ProtoFile f = parse(src);
            assertEquals(1, f.enums().size());
            EnumDecl e = f.enums().get(0);
            assertEquals("Role", e.name());
            assertEquals(3, e.values().size());
            assertEquals("USER", e.values().get(0).name());
            assertEquals(2, e.values().get(2).number());
        }

        @Test
        void nested_enum_in_message() {
            String src = """
                    syntax = "proto3";
                    message M {
                      enum Color { RED = 0; BLUE = 1; }
                      Color c = 1;
                    }
                    """;
            ProtoFile f = parse(src);
            assertEquals(1, f.messages().get(0).nestedEnums().size());
        }
    }

    @Nested
    @DisplayName("Comments + whitespace")
    class CommentsAndWs {

        @Test
        void line_and_block_comments_skipped() {
            String src = """
                    // top comment
                    syntax = "proto3";
                    /* block comment
                       on multiple lines */
                    message M {
                      // field comment
                      int32 x = 1; /* trailing */
                    }
                    """;
            ProtoFile f = parse(src);
            assertEquals(1, f.messages().get(0).fields().size());
        }
    }

    @Nested
    @DisplayName("Resilience — features ignorées en M2.1")
    class Resilience {

        @Test
        void services_parsed_with_methods() {
            String src = """
                    syntax = "proto3";
                    message Req {}
                    message Resp {}
                    service S {
                      rpc Foo (Req) returns (Resp);
                      rpc Bar (stream Req) returns (stream Resp);
                    }
                    """;
            ProtoFile f = parse(src);
            assertEquals(2, f.messages().size());
            assertEquals(1, f.services().size());
            ProtoAst.ServiceDecl svc = f.services().get(0);
            assertEquals("S", svc.name());
            assertEquals(2, svc.methods().size());
            assertEquals("Foo", svc.methods().get(0).name());
            assertEquals("Req", svc.methods().get(0).inputType());
            assertEquals("Resp", svc.methods().get(0).outputType());
            assertEquals(false, svc.methods().get(0).clientStreaming());
            assertEquals(true, svc.methods().get(1).clientStreaming());
            assertEquals(true, svc.methods().get(1).serverStreaming());
        }

        @Test
        void oneof_skipped() {
            String src = """
                    syntax = "proto3";
                    message M {
                      string a = 1;
                      oneof choice {
                        string x = 2;
                        int32 y = 3;
                      }
                    }
                    """;
            ProtoFile f = parse(src);
            // Le champ avant l'oneof est conservé ; les champs dans l'oneof
            // sont silencieusement ignorés en M2.1.
            assertEquals(1, f.messages().get(0).fields().size());
        }

        @Test
        void file_level_options_collected() {
            String src = """
                    syntax = "proto3";
                    option java_package = "io.example";
                    option (foo.bar) = "baz";
                    """;
            ProtoFile f = parse(src);
            assertEquals(2, f.options().size());
            assertEquals("java_package", f.options().get(0).name());
        }
    }
}
