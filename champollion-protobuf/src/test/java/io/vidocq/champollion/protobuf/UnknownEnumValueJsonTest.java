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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * M6.9 — Préservation des unknown enum values en proto3 JSON.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 §JSON Mapping</a> :
 * <blockquote>
 *   Enum value sent as integer is preserved, including unknown values.
 * </blockquote>
 *
 * <p>Quand un parser proto3 JSON rencontre une valeur enum entière qui n'est pas
 * dans son schéma local (ex: {@code 42} pour un enum [USER=0, ADMIN=1]), il doit
 * <em>préserver</em> cette valeur via le {@link UnknownFieldSet} du message et
 * la <em>ré-émettre</em> en numeric lors d'un toJson ultérieur.
 *
 * <p>Cible TCK Google : {@code Required.Proto3.JsonInput.EnumFieldUnknownValue.Validator}.</p>
 */
class UnknownEnumValueJsonTest {

    public enum Role { USER, ADMIN }

    @ProtobufMessage
    public record Account(
            @ProtobufField(number = 1, type = FieldType.STRING) String login,
            @ProtobufField(number = 2, type = FieldType.ENUM) Role role,
            UnknownFieldSet unknownFields
    ) implements Message {}

    @ProtobufMessage
    public record AccountWithRoles(
            @ProtobufField(number = 1, type = FieldType.STRING) String login,
            @ProtobufField(number = 2, type = FieldType.ENUM) List<Role> roles,
            UnknownFieldSet unknownFields
    ) implements Message {}

    @Nested
    @DisplayName("Read — capture unknown enum value dans UnknownFieldSet")
    class Read {

        @Test
        void single_unknown_enum_value_is_captured() {
            Account a = ProtobufJson.fromJson(Account.class, "{\"login\":\"alice\",\"role\":42}");
            assertEquals("alice", a.login());
            // proto3 §implicit-presence : le slot Java reçoit le default (Role.values()[0])
            // car aucun Java enum constant ne matche la value 42. La vraie info est
            // dans UnknownFieldSet — c'est elle qui permet la ré-émission JSON numeric.
            assertEquals(Role.USER, a.role(),
                    "default proto3 quand l'unknown value n'est pas mappable au Java enum");
            assertNotNull(a.unknownFields());
            UnknownFieldSet.Field f = a.unknownFields().get(2);
            assertNotNull(f, "fieldNumber 2 doit être dans UnknownFieldSet");
            assertEquals(List.of(42L), f.varints());
        }

        @Test
        void known_enum_value_does_not_pollute_unknown_set() {
            Account a = ProtobufJson.fromJson(Account.class, "{\"login\":\"bob\",\"role\":\"ADMIN\"}");
            assertEquals(Role.ADMIN, a.role());
            assertNotNull(a.unknownFields());
            assertTrue(a.unknownFields().isEmpty(),
                    "valeur enum connue : pas d'ajout au UnknownFieldSet");
        }

        @Test
        void repeated_enum_mixes_known_and_unknown() {
            AccountWithRoles a = ProtobufJson.fromJson(AccountWithRoles.class,
                    "{\"login\":\"x\",\"roles\":[\"USER\",42,\"ADMIN\",99]}");
            assertEquals(List.of(Role.USER, Role.ADMIN), a.roles(),
                    "liste known ne contient que les valeurs mappables Java");
            UnknownFieldSet.Field f = a.unknownFields().get(2);
            assertNotNull(f);
            assertEquals(List.of(42L, 99L), f.varints(),
                    "unknown values préservées dans l'ordre d'apparition");
        }
    }

    @Nested
    @DisplayName("Write — ré-émission numeric depuis UnknownFieldSet")
    class Write {

        @Test
        void single_unknown_enum_is_re_emitted_as_numeric() {
            UnknownFieldSet.Builder b1 = UnknownFieldSet.newBuilder();
            b1.recordVarint(2, 42L);
            UnknownFieldSet ufs = b1.build();
            // role = Role.USER (default proto3) mais l'unknown value 42 du UnknownFieldSet doit primer.
            Account a = new Account("alice", Role.USER, ufs);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"role\":42"),
                    "unknown enum doit être ré-émis numeric, got " + json);
            assertTrue(json.contains("\"login\":\"alice\""), json);
        }

        @Test
        void repeated_enum_merges_known_and_unknown_in_one_array() {
            UnknownFieldSet.Builder b2 = UnknownFieldSet.newBuilder();
            b2.recordVarint(2, 42L);
            b2.recordVarint(2, 99L);
            UnknownFieldSet ufs = b2.build();
            AccountWithRoles a = new AccountWithRoles("x", List.of(Role.USER, Role.ADMIN), ufs);
            String json = ProtobufJson.toJson(a);
            // L'array doit contenir : "USER", "ADMIN" (known) puis 42, 99 (unknown)
            assertTrue(json.contains("\"roles\":[\"USER\",\"ADMIN\",42,99]"),
                    "known + unknown enum mergés dans la même array, got " + json);
        }

        @Test
        void round_trip_preserves_unknown_enum_value() {
            String input = "{\"login\":\"alice\",\"role\":42}";
            Account a = ProtobufJson.fromJson(Account.class, input);
            String json = ProtobufJson.toJson(a);
            assertTrue(json.contains("\"role\":42"),
                    "round-trip JSON→Java→JSON doit préserver la valeur numeric, got " + json);
            assertTrue(json.contains("\"login\":\"alice\""), json);
        }

        @Test
        void round_trip_repeated_preserves_order_per_occurrence() {
            String input = "{\"login\":\"x\",\"roles\":[\"USER\",42,\"ADMIN\",99]}";
            AccountWithRoles a = ProtobufJson.fromJson(AccountWithRoles.class, input);
            String json = ProtobufJson.toJson(a);
            // Note : known et unknown sont séparés en deux groupes lors du re-emit
            // (known d'abord, unknown après). L'ordre exact intra-groupe est préservé.
            assertTrue(json.contains("\"roles\":[\"USER\",\"ADMIN\",42,99]"),
                    "round-trip array : known groupés en tête, unknown en queue, got " + json);
        }
    }
}
