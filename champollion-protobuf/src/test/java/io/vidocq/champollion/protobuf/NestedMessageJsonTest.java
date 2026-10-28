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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A nested message is written with the same rules as the top-level one: its map fields, its explicit-presence
 * fields holding a default value, and the unknown enum numbers its {@link UnknownFieldSet} keeps (in place of the
 * field for a singular enum, appended to the array of a repeated one).
 */
class NestedMessageJsonTest {

    public enum Role { USER, ADMIN }

    @ProtobufMessage
    public record Account(
            @ProtobufField(number = 1, type = FieldType.ENUM) Role role,
            @ProtobufField(number = 2, type = FieldType.ENUM) List<Role> roles,
            @ProtobufField(number = 3, type = FieldType.STRING) String login,
            UnknownFieldSet unknownFields
    ) implements Message {}

    @ProtobufMessage
    public record Holder(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Account account
    ) implements Message {}

    @ProtobufMessage
    public record Settings(
            @ProtobufField(number = 1, type = FieldType.MAP) Map<String, String> values,
            @ProtobufField(number = 2, type = FieldType.INT32, explicitPresence = true) Integer limit
    ) implements Message {}

    @ProtobufMessage
    public record Config(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Settings settings
    ) implements Message {}

    @Test
    void the_top_level_reference_form() {
        Account a = ProtobufJson.fromJson(Account.class, "{\"role\":7,\"roles\":[1,9],\"login\":\"x\"}");
        assertEquals("{\"role\":7,\"roles\":[\"ADMIN\",9],\"login\":\"x\"}", ProtobufJson.toJson(a));
    }

    @Test
    void a_nested_message_writes_its_unknown_enum_numbers_in_the_same_form() {
        Holder h = ProtobufJson.fromJson(Holder.class,
                "{\"account\":{\"role\":7,\"roles\":[1,9],\"login\":\"x\"}}");
        assertEquals("{\"account\":{\"role\":7,\"roles\":[\"ADMIN\",9],\"login\":\"x\"}}", ProtobufJson.toJson(h));
    }

    @Test
    void a_nested_message_without_unknown_numbers_is_written_normally() {
        Holder h = new Holder(new Account(Role.ADMIN, List.of(Role.USER), "y", UnknownFieldSet.EMPTY));
        assertEquals("{\"account\":{\"role\":\"ADMIN\",\"roles\":[\"USER\"],\"login\":\"y\"}}", ProtobufJson.toJson(h));
        assertEquals(h, ProtobufJson.fromJson(Holder.class, ProtobufJson.toJson(h)));
    }

    @Test
    void a_nested_message_writes_its_maps_and_its_present_defaults() {
        Settings settings = new Settings(Map.of("k", "v"), 0);
        assertEquals("{\"values\":{\"k\":\"v\"},\"limit\":0}", ProtobufJson.toJson(settings));
        Config c = new Config(settings);
        String json = ProtobufJson.toJson(c);
        assertEquals("{\"settings\":{\"values\":{\"k\":\"v\"},\"limit\":0}}", json);
        assertEquals(c, ProtobufJson.fromJson(Config.class, json));
    }
}
