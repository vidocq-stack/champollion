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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M5.5.2 — JSON canonical proto3 pour {@code uint32}, {@code uint64},
 * {@code fixed32}, {@code fixed64} : valeurs unsigned préservées en lecture
 * et écriture.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/json/#json-options">
 * Proto3 JSON Mapping</a> — uint32 sort en number ≥ 0, uint64 sort en string.</p>
 */
class UnsignedJsonTest {

    @ProtobufMessage
    public record UnsignedHolder(
            @ProtobufField(number = 1, type = FieldType.UINT32) int u32,
            @ProtobufField(number = 2, type = FieldType.UINT64) long u64,
            @ProtobufField(number = 3, type = FieldType.FIXED32) int f32,
            @ProtobufField(number = 4, type = FieldType.FIXED64) long f64) {}

    @Nested
    @DisplayName("Écriture JSON canonical")
    class Write {

        @Test
        void uint32_max_value_serialised_as_positive_number() {
            // 0xFFFFFFFF = -1 en Java signed = 4294967295 en unsigned
            String json = ProtobufJson.toJson(new UnsignedHolder(-1, 0L, 0, 0L));
            assertEquals("{\"u32\":4294967295}", json);
        }

        @Test
        void fixed32_max_value_serialised_as_positive_number() {
            String json = ProtobufJson.toJson(new UnsignedHolder(0, 0L, -1, 0L));
            assertEquals("{\"f32\":4294967295}", json);
        }

        @Test
        void uint64_max_value_serialised_as_string_unsigned() {
            // 0xFFFFFFFFFFFFFFFF = -1 en Java signed = 18446744073709551615 unsigned
            String json = ProtobufJson.toJson(new UnsignedHolder(0, -1L, 0, 0L));
            assertEquals("{\"u64\":\"18446744073709551615\"}", json);
        }

        @Test
        void fixed64_max_value_serialised_as_string_unsigned() {
            String json = ProtobufJson.toJson(new UnsignedHolder(0, 0L, 0, -1L));
            assertEquals("{\"f64\":\"18446744073709551615\"}", json);
        }
    }

    @Nested
    @DisplayName("Lecture JSON canonical")
    class Read {

        @Test
        void uint32_max_value_parsed_as_signed_int_bit_preserving() {
            UnsignedHolder m = ProtobufJson.fromJson(UnsignedHolder.class,
                    "{\"u32\":4294967295}");
            assertEquals(-1, m.u32());
        }

        @Test
        void uint64_max_value_parsed_as_signed_long_bit_preserving() {
            UnsignedHolder m = ProtobufJson.fromJson(UnsignedHolder.class,
                    "{\"u64\":\"18446744073709551615\"}");
            assertEquals(-1L, m.u64());
        }

        @Test
        void uint32_overflow_rejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> ProtobufJson.fromJson(UnsignedHolder.class,
                            "{\"u32\":4294967296}"));
        }
    }
}
