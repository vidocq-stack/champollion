package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SchemaResolver} — résolution sémantique .proto → Descriptors.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/#identifiers">Proto3 §Type Lookup</a>.</p>
 */
class SchemaResolverTest {

    private Descriptors.FileDescriptor resolve(String src) {
        return SchemaResolver.resolve(ProtoParser.parse("inline.proto", src));
    }

    @Nested
    @DisplayName("Conversion scalaires + cardinalités")
    class Scalars {

        @Test
        void scalars_mapped_to_FieldType() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M {
                      double d = 1;
                      float f = 2;
                      int32 i32 = 3;
                      int64 i64 = 4;
                      string s = 5;
                      bytes b = 6;
                      bool flag = 7;
                    }
                    """);
            Descriptors.Descriptor m = f.findMessageType("M");
            assertEquals(FieldType.DOUBLE, m.findFieldByNumber(1).type());
            assertEquals(FieldType.FLOAT, m.findFieldByNumber(2).type());
            assertEquals(FieldType.INT32, m.findFieldByNumber(3).type());
            assertEquals(FieldType.INT64, m.findFieldByNumber(4).type());
            assertEquals(FieldType.STRING, m.findFieldByNumber(5).type());
            assertEquals(FieldType.BYTES, m.findFieldByNumber(6).type());
            assertEquals(FieldType.BOOL, m.findFieldByNumber(7).type());
        }

        @Test
        void repeated_marks_cardinality_and_packed() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M {
                      repeated int32 nums = 1;
                      repeated string tags = 2;
                    }
                    """);
            Descriptors.Descriptor m = f.findMessageType("M");
            Descriptors.FieldDescriptor nums = m.findFieldByNumber(1);
            Descriptors.FieldDescriptor tags = m.findFieldByNumber(2);
            assertEquals(Descriptors.Cardinality.REPEATED, nums.cardinality());
            // int32 est packable → packed=true par défaut proto3
            assertEquals(true, nums.packed());
            // string n'est PAS packable
            assertEquals(false, tags.packed());
        }

        @Test
        void singular_proto3_is_implicit() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M { string s = 1; }
                    """);
            assertEquals(Descriptors.Cardinality.IMPLICIT,
                    f.findMessageType("M").findFieldByNumber(1).cardinality());
        }

        @Test
        void optional_proto3_is_explicit() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M { optional string s = 1; }
                    """);
            assertEquals(Descriptors.Cardinality.EXPLICIT,
                    f.findMessageType("M").findFieldByNumber(1).cardinality());
        }
    }

    @Nested
    @DisplayName("Type lookup — messages et enums")
    class Lookup {

        @Test
        void enum_field_resolved() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    enum Role { USER = 0; ADMIN = 1; }
                    message Account { Role role = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("Account").findFieldByNumber(1);
            assertEquals(FieldType.ENUM, fd.type());
            assertEquals("Role", fd.enumTypeName());
        }

        @Test
        void message_field_resolved_simple_name() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message Inner { int32 x = 1; }
                    message Outer { Inner i = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("Outer").findFieldByNumber(1);
            assertEquals(FieldType.MESSAGE, fd.type());
            assertEquals("Inner", fd.messageTypeName());
        }

        @Test
        void nested_message_lookup() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message Outer {
                      message Inner { int32 x = 1; }
                      Inner i = 1;
                    }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("Outer").findFieldByNumber(1);
            assertEquals("Outer.Inner", fd.messageTypeName());
        }

        @Test
        void fully_qualified_with_leading_dot() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    package my.pkg;
                    message A { int32 x = 1; }
                    message B { .my.pkg.A a = 1; }
                    """);
            assertNotNull(f.findMessageType("A"));
            Descriptors.FieldDescriptor inB = f.findMessageType("B").findFieldByNumber(1);
            assertEquals("my.pkg.A", inB.messageTypeName());
        }

        @Test
        void package_prefix_in_fullName() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    package x.y;
                    message M { int32 i = 1; }
                    """);
            assertEquals("x.y", f.packageName());
            assertEquals("x.y.M", f.findMessageType("M").fullName());
        }

        @Test
        void unresolved_type_throws() {
            assertThrows(SchemaResolver.SchemaResolutionException.class, () -> resolve("""
                    syntax = "proto3";
                    message A { Missing m = 1; }
                    """));
        }
    }

    @Nested
    @DisplayName("Syntax — proto2/proto3/Editions")
    class SyntaxMapping {

        @Test
        void proto3_syntax() {
            assertEquals(Descriptors.Syntax.PROTO3,
                    resolve("syntax = \"proto3\";").syntax());
        }

        @Test
        void proto2_syntax() {
            assertEquals(Descriptors.Syntax.PROTO2,
                    resolve("syntax = \"proto2\";").syntax());
        }

        @Test
        void edition_2023() {
            assertEquals(Descriptors.Syntax.EDITION_2023,
                    resolve("edition = \"2023\";").syntax());
        }
    }

    @Nested
    @DisplayName("JsonName + lookup helpers")
    class Helpers {

        @Test
        void jsonName_auto_derived() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M { string first_name = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals("firstName", fd.jsonName());
        }

        @Test
        void findFieldByJsonName_works() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M { string first_name = 1; }
                    """);
            assertEquals("first_name",
                    f.findMessageType("M").findFieldByJsonName("firstName").name());
            assertNull(f.findMessageType("M").findFieldByJsonName("unknown"));
        }
    }
}
