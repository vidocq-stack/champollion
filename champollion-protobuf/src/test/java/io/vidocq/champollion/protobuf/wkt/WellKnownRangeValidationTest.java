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
package io.vidocq.champollion.protobuf.wkt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.ProtobufJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M5.5.3 — validation des ranges {@code Timestamp} / {@code Duration}
 * exigée par la conformance Google proto3.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#timestamp">
 * Timestamp.proto</a> et <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#duration">
 * Duration.proto</a>.</p>
 */
class WellKnownRangeValidationTest {

    @Nested
    @DisplayName("Timestamp — bornes 0001-01-01..9999-12-31")
    class TimestampRange {

        @Test
        void seconds_too_large_rejected() {
            // 9999-12-31T23:59:59.999_999_999Z = 253402300799
            // +1 doit déclencher l'erreur.
            Timestamp t = new Timestamp(253402300800L, 0);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(t));
        }

        @Test
        void seconds_too_small_rejected() {
            // 0001-01-01T00:00:00Z = -62135596800
            // -1 doit déclencher l'erreur.
            Timestamp t = new Timestamp(-62135596801L, 0);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(t));
        }

        @Test
        void nanos_too_large_rejected() {
            Timestamp t = new Timestamp(0L, 1_000_000_000);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(t));
        }

        @Test
        void nanos_negative_rejected() {
            Timestamp t = new Timestamp(0L, -1);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(t));
        }

        @Test
        void epoch_passes() {
            assertEquals("\"1970-01-01T00:00:00Z\"", ProtobufJson.toJson(new Timestamp(0L, 0)));
        }
    }

    @Nested
    @DisplayName("Duration — bornes ±315_576_000_000s")
    class DurationRange {

        @Test
        void seconds_too_large_rejected() {
            Duration d = new Duration(315_576_000_001L, 0);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(d));
        }

        @Test
        void seconds_too_small_rejected() {
            Duration d = new Duration(-315_576_000_001L, 0);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(d));
        }

        @Test
        void nanos_out_of_range_rejected() {
            Duration d = new Duration(0L, 1_000_000_000);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(d));
        }

        @Test
        void mixed_signs_rejected() {
            // seconds + et nanos − interdit (spec : même signe sauf si un = 0).
            Duration d = new Duration(1L, -1);
            assertThrows(IllegalArgumentException.class, () -> ProtobufJson.toJson(d));
        }

        @Test
        void zero_seconds_negative_nanos_allowed() {
            // 0s -500ns : ok (seconds = 0, le test de signe ne s'applique pas).
            String s = ProtobufJson.toJson(new Duration(0L, -500_000_000));
            assertEquals("\"-0.500s\"", s);
        }

        @Test
        void edge_max_passes() {
            // Exactement à la borne, doit passer.
            String s = ProtobufJson.toJson(new Duration(315_576_000_000L, 999_999_999));
            assertEquals("\"315576000000.999999999s\"", s);
        }
    }
}
