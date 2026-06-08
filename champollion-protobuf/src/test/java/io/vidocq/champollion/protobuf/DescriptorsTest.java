/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Descriptors}.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#file-descriptor-set">descriptor.proto</a>
 * (modèle réflexif des types). M1.4 — data model + synthèse via réflexion.</p>
 */
class DescriptorsTest {

    @ProtobufMessage
    public record SnakeRecord(
            @ProtobufField(number = 1, type = FieldType.STRING) String first_name,
            @ProtobufField(number = 2, type = FieldType.INT32) int year_of_birth,
            @ProtobufField(number = 3, type = FieldType.STRING) List<String> nick_names
    ) implements Message {}

    @Nested
    @DisplayName("forRecord — synthèse depuis @ProtobufMessage")
    class Synthesize {

        @Test
        void person_record_yields_descriptor() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            assertEquals("Person", d.name());
            assertTrue(d.fullName().endsWith("Person"));
            assertEquals(3, d.fields().size());
        }

        @Test
        void cached_by_class() {
            Descriptors.Descriptor a = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.Descriptor b = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            assertSame(a, b);
        }

        @Test
        void non_record_throws() {
            assertThrows(IllegalArgumentException.class, () -> Descriptors.forRecord(String.class));
        }

        @Test
        void record_without_annotation_throws() {
            record Plain(String x) {}
            assertThrows(IllegalArgumentException.class, () -> Descriptors.forRecord(Plain.class));
        }

        @Test
        void fields_sorted_by_number() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            int prev = 0;
            for (Descriptors.FieldDescriptor fd : d.fields()) {
                assertTrue(fd.number() > prev);
                prev = fd.number();
            }
        }
    }

    @Nested
    @DisplayName("FieldDescriptor — find par numéro / nom / jsonName")
    class FieldLookup {

        @Test
        void find_by_number() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.FieldDescriptor f1 = d.findFieldByNumber(1);
            assertEquals("name", f1.name());
            assertEquals(FieldType.STRING, f1.type());
        }

        @Test
        void find_by_name() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.FieldDescriptor age = d.findFieldByName("age");
            assertEquals(2, age.number());
        }

        @Test
        void unknown_returns_null() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            assertNull(d.findFieldByNumber(999));
            assertNull(d.findFieldByName("nope"));
        }

        @Test
        void cardinality_repeated_for_list_components() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.FieldDescriptor tags = d.findFieldByName("tags");
            assertEquals(Descriptors.Cardinality.REPEATED, tags.cardinality());
            assertTrue(tags.isRepeated());
        }

        @Test
        void cardinality_implicit_for_scalars_proto3() {
            Descriptors.Descriptor d = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.FieldDescriptor name = d.findFieldByName("name");
            assertEquals(Descriptors.Cardinality.IMPLICIT, name.cardinality());
        }
    }

    @Nested
    @DisplayName("toJsonName — snake_case → camelCase per Proto3 JSON Mapping")
    class JsonNaming {

        @Test
        void simple_names_unchanged() {
            assertEquals("name", Descriptors.toJsonName("name"));
            assertEquals("age", Descriptors.toJsonName("age"));
        }

        @Test
        void underscores_become_camel() {
            assertEquals("firstName", Descriptors.toJsonName("first_name"));
            assertEquals("yearOfBirth", Descriptors.toJsonName("year_of_birth"));
        }

        @Test
        void synthesized_descriptor_uses_jsonName() {
            Descriptors.Descriptor d = Descriptors.forRecord(SnakeRecord.class);
            assertEquals("firstName", d.findFieldByName("first_name").jsonName());
            assertEquals("yearOfBirth", d.findFieldByName("year_of_birth").jsonName());
            assertEquals("nickNames", d.findFieldByName("nick_names").jsonName());
        }

        @Test
        void findFieldByJsonName_lookup() {
            Descriptors.Descriptor d = Descriptors.forRecord(SnakeRecord.class);
            Descriptors.FieldDescriptor fd = d.findFieldByJsonName("firstName");
            assertNotNull(fd);
            assertEquals("first_name", fd.name());
        }
    }

    @Nested
    @DisplayName("FileDescriptor — agrégation explicite")
    class File {

        @Test
        void construct_file_descriptor_manually() {
            Descriptors.Descriptor person = Descriptors.forRecord(ProtobufRuntimeTest.Person.class);
            Descriptors.FileDescriptor file = new Descriptors.FileDescriptor(
                    "person.proto",
                    "io.vidocq.champollion.protobuf",
                    Descriptors.Syntax.PROTO3,
                    List.of(person),
                    List.of());

            assertEquals("person.proto", file.name());
            assertEquals(Descriptors.Syntax.PROTO3, file.syntax());
            assertSame(person, file.findMessageType("Person"));
            assertNull(file.findMessageType("Unknown"));
        }
    }

    @Nested
    @DisplayName("Field number bounds")
    class Bounds {

        @Test
        void field_number_zero_invalid() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Descriptors.FieldDescriptor(
                            "x", "x", 0, FieldType.INT32,
                            Descriptors.Cardinality.IMPLICIT, false, null, null));
        }

        @Test
        void field_number_too_large_invalid() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Descriptors.FieldDescriptor(
                            "x", "x", 1 << 30, FieldType.INT32,
                            Descriptors.Cardinality.IMPLICIT, false, null, null));
        }
    }
}
