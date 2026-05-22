package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M4.2 — propagation features file → message → field.
 *
 * <p>Spec : <a href="https://protobuf.dev/editions/features/">Edition Features</a>.</p>
 */
class FeaturesPropagationTest {

    private Descriptors.FileDescriptor resolve(String src) {
        return SchemaResolver.resolve(ProtoParser.parse("inline.proto", src));
    }

    @Nested
    @DisplayName("Defaults par syntax")
    class Defaults {

        @Test
        void proto3_field_inherits_proto3_features() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message M { string s = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.FieldPresence.IMPLICIT, fd.features().fieldPresence());
            assertEquals(Descriptors.RepeatedFieldEncoding.PACKED, fd.features().repeatedFieldEncoding());
        }

        @Test
        void proto2_field_inherits_proto2_features() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto2";
                    message M { optional string s = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.FieldPresence.EXPLICIT, fd.features().fieldPresence());
            assertEquals(Descriptors.EnumType.CLOSED, fd.features().enumType());
        }

        @Test
        void edition_2023_aligns_with_proto3() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    message M { string s = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.FieldPresence.IMPLICIT, fd.features().fieldPresence());
        }
    }

    @Nested
    @DisplayName("File-level overrides")
    class FileLevel {

        @Test
        void file_features_field_presence_explicit() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    option features.field_presence = EXPLICIT;
                    message M { string s = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.FieldPresence.EXPLICIT, fd.features().fieldPresence());
        }

        @Test
        void file_features_enum_type_closed() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    option features.enum_type = CLOSED;
                    message M { string s = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.EnumType.CLOSED, fd.features().enumType());
        }

        @Test
        void file_features_repeated_encoding_expanded_propagates_to_packed_flag() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    option features.repeated_field_encoding = EXPANDED;
                    message M { repeated int32 nums = 1; }
                    """);
            Descriptors.FieldDescriptor fd = f.findMessageType("M").findFieldByNumber(1);
            assertEquals(Descriptors.RepeatedFieldEncoding.EXPANDED,
                    fd.features().repeatedFieldEncoding());
            assertFalse(fd.packed(),
                    "packed flag doit refléter le repeated_field_encoding hérité");
        }
    }

    @Nested
    @DisplayName("Message-level overrides — hérite du file mais peut surcharger")
    class MessageLevel {

        @Test
        void message_overrides_file_feature() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    option features.field_presence = EXPLICIT;
                    message Loose {
                      option features.field_presence = IMPLICIT;
                      string s = 1;
                    }
                    message Strict {
                      string s = 1;
                    }
                    """);
            // Loose : override message → IMPLICIT
            assertEquals(Descriptors.FieldPresence.IMPLICIT,
                    f.findMessageType("Loose").findFieldByNumber(1).features().fieldPresence());
            // Strict : pas d'override → hérite du file EXPLICIT
            assertEquals(Descriptors.FieldPresence.EXPLICIT,
                    f.findMessageType("Strict").findFieldByNumber(1).features().fieldPresence());
        }

        @Test
        void nested_message_inherits_outer_message_features() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    message Outer {
                      option features.utf8_validation = NONE;
                      message Inner { string s = 1; }
                    }
                    """);
            // Inner hérite du Outer
            Descriptors.FieldDescriptor fd = f.findMessageType("Outer")
                    .nestedMessageTypes().get(0).findFieldByNumber(1);
            assertEquals(Descriptors.Utf8Validation.NONE, fd.features().utf8Validation());
        }
    }

    @Nested
    @DisplayName("Toutes les features parseables")
    class All {

        @Test
        void all_six_features_parseable() {
            Descriptors.FileDescriptor f = resolve("""
                    edition = "2023";
                    option features.field_presence = LEGACY_REQUIRED;
                    option features.enum_type = CLOSED;
                    option features.repeated_field_encoding = EXPANDED;
                    option features.utf8_validation = NONE;
                    option features.message_encoding = DELIMITED;
                    option features.json_format = LEGACY_BEST_EFFORT;
                    message M { string s = 1; }
                    """);
            Descriptors.Features fs = f.findMessageType("M").findFieldByNumber(1).features();
            assertEquals(Descriptors.FieldPresence.LEGACY_REQUIRED, fs.fieldPresence());
            assertEquals(Descriptors.EnumType.CLOSED, fs.enumType());
            assertEquals(Descriptors.RepeatedFieldEncoding.EXPANDED, fs.repeatedFieldEncoding());
            assertEquals(Descriptors.Utf8Validation.NONE, fs.utf8Validation());
            assertEquals(Descriptors.MessageEncoding.DELIMITED, fs.messageEncoding());
            assertEquals(Descriptors.JsonFormat.LEGACY_BEST_EFFORT, fs.jsonFormat());
        }
    }
}
