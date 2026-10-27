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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Absent, empty and null values of map, repeated and enum fields, written as JSON and on the wire. */
class AbsentFieldWritingTest {

    public enum Role { USER, ADMIN }

    @ProtobufMessage
    public record Roles(
            @ProtobufField(number = 1, type = FieldType.ENUM) List<Role> roles,
            UnknownFieldSet unknownFields) implements Message {}

    @ProtobufMessage
    public record Entries(
            @ProtobufField(number = 1, type = FieldType.MAP,
                           mapKey = FieldType.STRING, mapValue = FieldType.INT32)
            Map<String, Integer> entries) {}

    @Test
    void null_and_empty_repeated_fields_are_omitted() {
        assertEquals("{}", ProtobufJson.toJson(new Roles(null, null)));
        assertEquals("{}", ProtobufJson.toJson(new Roles(List.of(), null)));
    }

    @Test
    void unknown_enum_values_are_written_without_known_values() {
        UnknownFieldSet.Builder unknown = UnknownFieldSet.newBuilder();
        unknown.recordVarint(1, 42L);

        assertEquals("{\"roles\":[42]}", ProtobufJson.toJson(new Roles(null, unknown.build())));
    }

    @Test
    void null_items_of_a_repeated_enum_are_skipped() {
        List<Role> roles = new ArrayList<>(Arrays.asList(Role.ADMIN, null));

        assertEquals("{\"roles\":[\"ADMIN\"]}", ProtobufJson.toJson(new Roles(roles, null)));
    }

    @Test
    void null_and_empty_maps_are_omitted() {
        assertEquals("{}", ProtobufJson.toJson(new Entries(null)));
        assertEquals("{}", ProtobufJson.toJson(new Entries(Map.of())));
        assertEquals("{\"entries\":{\"a\":1}}", ProtobufJson.toJson(new Entries(Map.of("a", 1))));
    }

    @Test
    void a_null_map_key_is_written_as_the_key_default() throws IOException {
        Map<String, Integer> entries = new HashMap<>();
        entries.put(null, 7);

        byte[] wire = Protobuf.toByteArray(new Entries(entries));

        assertEquals(Map.of("", 7), Protobuf.parser(Entries.class).parseFrom(wire).entries());
    }

    @Test
    void a_negative_int32_on_ten_bytes_is_read_from_an_array() throws IOException {
        byte[] wire = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                       (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01, 0, 0};

        CodedInputStream in = CodedInputStream.newInstance(wire);

        assertEquals(-1, in.readRawVarint32());
        assertEquals(0, in.readRawVarint32());
    }

    @Test
    void an_int32_varint_ending_early_in_its_sign_extension_is_read() throws IOException {
        byte[] wire = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x8F, 0x00,
                       5, 0, 0, 0, 0, 0};

        CodedInputStream in = CodedInputStream.newInstance(wire);

        assertEquals(-1, in.readRawVarint32());
        assertEquals(5, in.readRawVarint32());
    }

    @Test
    void an_int32_varint_longer_than_ten_bytes_is_rejected() {
        byte[] wire = new byte[12];
        Arrays.fill(wire, (byte) 0xFF);

        CodedInputStream in = CodedInputStream.newInstance(wire);

        assertThrows(MalformedProtobufException.class, in::readRawVarint32);
    }
}
