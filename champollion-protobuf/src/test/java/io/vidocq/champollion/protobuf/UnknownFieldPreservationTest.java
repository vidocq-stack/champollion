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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unknown fields of every wire type survive a parse / serialize cycle byte for byte, and {@link UnknownFieldSet}
 * compares them by content.
 */
class UnknownFieldPreservationTest {

    @ProtobufMessage
    public record Known(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            UnknownFieldSet unknownFields
    ) implements Message {}

    private static byte[] withUnknownFields(byte[] blob) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "n");
        out.writeUInt64(2, 300);
        out.writeFixed64(3, -1L);
        out.writeFixed32(4, 7);
        out.writeBytes(5, blob);
        out.writeUInt64(2, 1);
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    void unknown_fields_of_every_wire_type_are_written_back_unchanged() throws IOException {
        byte[] original = withUnknownFields(new byte[] {1, 2, 3});
        Known k = Protobuf.parser(Known.class).parseFrom(original);
        assertEquals("n", k.name());
        assertEquals(original.length, k.getSerializedSize());
        assertArrayEquals(original, k.toByteArray());
    }

    @Test
    void unknown_fields_are_grouped_by_number_and_wire_type() throws IOException {
        UnknownFieldSet set = Protobuf.parser(Known.class).parseFrom(withUnknownFields(new byte[] {9})).unknownFields();
        assertFalse(set.isEmpty());
        assertEquals(List.of(2, 3, 4, 5), List.copyOf(set.asMap().keySet()));
        assertEquals(List.of(300L, 1L), set.get(2).varints());
        assertEquals(List.of(-1L), set.get(3).fixed64s());
        assertEquals(List.of(7), set.get(4).fixed32s());
        assertArrayEquals(new byte[] {9}, set.get(5).lengthDelimited().get(0));
        assertNull(set.get(6));
        var view = set.asMap();
        assertThrows(UnsupportedOperationException.class, view::clear);
    }

    @Test
    void sets_with_the_same_content_are_equal() throws IOException {
        UnknownFieldSet a = Protobuf.parser(Known.class).parseFrom(withUnknownFields(new byte[] {1})).unknownFields();
        UnknownFieldSet b = Protobuf.parser(Known.class).parseFrom(withUnknownFields(new byte[] {1})).unknownFields();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.get(5), b.get(5));
        assertEquals(a.get(5).hashCode(), b.get(5).hashCode());
    }

    @Test
    void sets_differing_in_bytes_or_in_count_are_not_equal() throws IOException {
        UnknownFieldSet one = Protobuf.parser(Known.class).parseFrom(withUnknownFields(new byte[] {1})).unknownFields();
        UnknownFieldSet other = Protobuf.parser(Known.class).parseFrom(withUnknownFields(new byte[] {2})).unknownFields();
        assertNotEquals(one, other);
        assertNotEquals(one.get(5), other.get(5));

        UnknownFieldSet.Builder twice = UnknownFieldSet.newBuilder();
        twice.recordLengthDelimited(5, new byte[] {1});
        twice.recordLengthDelimited(5, new byte[] {1});
        assertNotEquals(one.get(5), twice.build().get(5));
        assertNotEquals(one.get(2), one.get(5));
    }

    @Test
    void an_empty_builder_gives_the_empty_set() {
        UnknownFieldSet empty = UnknownFieldSet.newBuilder().build();
        assertTrue(empty.isEmpty());
        assertEquals(UnknownFieldSet.EMPTY, empty);
        assertEquals(0, empty.getSerializedSize());
    }
}
