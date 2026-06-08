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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link UnknownFieldSet}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#unknowns">Proto3 §Unknown Fields</a>
 * et Editions 2023 §Unknown Fields — un parser doit préserver tous les champs
 * dont le numéro n'est pas connu de son schéma local, pour que la sérialisation
 * suivante ne perde aucune information.</p>
 */
class UnknownFieldSetTest {

    @Nested
    @DisplayName("Builder — collecte via UnknownFieldRecorder")
    class Build {

        @Test
        void empty_set_is_singleton() {
            UnknownFieldSet set = UnknownFieldSet.newBuilder().build();
            assertTrue(set.isEmpty());
            assertEquals(0, set.getSerializedSize());
            assertEquals(UnknownFieldSet.EMPTY, set);
        }

        @Test
        void varint_collected() {
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            b.recordVarint(7, 150L);
            UnknownFieldSet set = b.build();
            assertFalse(set.isEmpty());
            UnknownFieldSet.Field f = set.get(7);
            assertEquals(List.of(150L), f.varints());
        }

        @Test
        void multiple_wire_types_same_field() {
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            b.recordVarint(3, 42L);
            b.recordFixed32(3, 0xCAFEBABE);
            b.recordFixed64(3, 0x1122334455667788L);
            b.recordLengthDelimited(3, new byte[]{1, 2, 3});
            UnknownFieldSet.Field f = b.build().get(3);
            assertEquals(List.of(42L), f.varints());
            assertEquals(List.of(0xCAFEBABE), f.fixed32s());
            assertEquals(List.of(0x1122334455667788L), f.fixed64s());
            assertArrayEquals(new byte[]{1, 2, 3}, f.lengthDelimited().get(0));
        }
    }

    @Nested
    @DisplayName("skipField(tag, recorder) — collecte automatique des champs inconnus")
    class SkipAndRecord {

        @Test
        void unknown_varint_field_recorded() throws IOException {
            // Tag (field 5, varint) | value 150
            CodedInputStream in = CodedInputStream.newInstance(new byte[]{0x28, (byte) 0x96, 0x01});
            int tag = in.readTag();
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            in.skipField(tag, b);
            assertEquals(List.of(150L), b.build().get(5).varints());
        }

        @Test
        void unknown_length_delimited_recorded() throws IOException {
            // Tag (field 9, LEN) | length 3 | bytes
            CodedInputStream in = CodedInputStream.newInstance(
                    new byte[]{0x4A, 0x03, 0x01, 0x02, 0x03});
            int tag = in.readTag();
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            in.skipField(tag, b);
            assertArrayEquals(new byte[]{1, 2, 3}, b.build().get(9).lengthDelimited().get(0));
        }

        @Test
        void unknown_fixed32_recorded() throws IOException {
            // Tag (field 4, FIXED32) | LE 32-bit
            CodedInputStream in = CodedInputStream.newInstance(
                    new byte[]{0x25, 0x78, 0x56, 0x34, 0x12});
            int tag = in.readTag();
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            in.skipField(tag, b);
            assertEquals(List.of(0x12345678), b.build().get(4).fixed32s());
        }
    }

    @Nested
    @DisplayName("writeTo + getSerializedSize — round-trip identique octet-à-octet")
    class WriteTo {

        @Test
        void varint_writeTo_matches_original_bytes() throws IOException {
            // Tag (field 5, varint) | value 150 → 0x28 0x96 0x01
            byte[] original = {0x28, (byte) 0x96, 0x01};

            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            CodedInputStream in = CodedInputStream.newInstance(original);
            int tag = in.readTag();
            in.skipField(tag, b);
            UnknownFieldSet set = b.build();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            set.writeTo(out);
            out.flush();

            assertArrayEquals(original, baos.toByteArray());
            assertEquals(original.length, set.getSerializedSize());
        }

        @Test
        void multiple_fields_preserve_first_insertion_order() throws IOException {
            UnknownFieldSet.Builder b = UnknownFieldSet.newBuilder();
            b.recordVarint(2, 7L);
            b.recordVarint(1, 1L);
            UnknownFieldSet set = b.build();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(baos);
            set.writeTo(out);
            out.flush();
            byte[] bytes = baos.toByteArray();

            // Ordre attendu : champ 2 puis champ 1 (LinkedHashMap preserve insertion order)
            CodedInputStream verify = CodedInputStream.newInstance(bytes);
            assertEquals(2, WireFormat.getTagFieldNumber(verify.readTag()));
            assertEquals(7L, verify.readRawVarint64());
            assertEquals(1, WireFormat.getTagFieldNumber(verify.readTag()));
            assertEquals(1L, verify.readRawVarint64());
        }
    }
}
