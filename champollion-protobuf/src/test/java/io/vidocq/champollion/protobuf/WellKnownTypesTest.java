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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Duration;
import io.vidocq.champollion.protobuf.wkt.Empty;
import io.vidocq.champollion.protobuf.wkt.FieldMask;
import io.vidocq.champollion.protobuf.wkt.Timestamp;
import io.vidocq.champollion.protobuf.wkt.Wrappers;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests JSON canonical des Well-Known Types google.protobuf.*.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 §JSON Mapping</a>,
 * sections Timestamp, Duration, Empty, Wrapper types, FieldMask.</p>
 */
class WellKnownTypesTest {

    @ProtobufMessage
    public record Outer(
            @ProtobufField(number = 1, type = FieldType.STRING) String name,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) Timestamp createdAt,
            @ProtobufField(number = 3, type = FieldType.MESSAGE) Duration ttl,
            @ProtobufField(number = 4, type = FieldType.MESSAGE) Wrappers.Int32Value count,
            @ProtobufField(number = 5, type = FieldType.MESSAGE) Wrappers.StringValue alias
    ) implements Message {}

    @Nested
    @DisplayName("Timestamp — RFC 3339 ISO-8601 avec suffixe Z")
    class Ts {

        @Test
        void epoch_serializes_to_1970_iso_string() {
            Timestamp t = new Timestamp(0L, 0);
            assertEquals("\"1970-01-01T00:00:00Z\"", ProtobufJson.toJson(t));
        }

        @Test
        void with_nanos() {
            Timestamp t = Timestamp.from(Instant.parse("2026-05-21T10:00:00.123456789Z"));
            String json = ProtobufJson.toJson(t);
            assertTrue(json.contains("2026-05-21T10:00:00.123456789Z"), "got " + json);
        }

        @Test
        void roundtrip() {
            Timestamp t = Timestamp.from(Instant.parse("2026-05-21T10:00:00Z"));
            Timestamp back = ProtobufJson.fromJson(Timestamp.class, ProtobufJson.toJson(t));
            assertEquals(t, back);
        }

        @Test
        void nested_in_outer_message() {
            Outer o = new Outer("x", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                    null, null, null);
            String json = ProtobufJson.toJson(o);
            assertTrue(json.contains("\"createdAt\":\"2026-01-01T00:00:00Z\""), "got " + json);
            Outer back = ProtobufJson.fromJson(Outer.class, json);
            assertEquals(o.createdAt(), back.createdAt());
        }
    }

    @Nested
    @DisplayName("Duration — '3.5s' avec précision 3/6/9 chiffres")
    class Dur {

        @Test
        void integer_seconds() {
            assertEquals("\"5s\"", ProtobufJson.toJson(new Duration(5, 0)));
        }

        @Test
        void with_milliseconds_precision_3() {
            assertEquals("\"3.500s\"", ProtobufJson.toJson(new Duration(3, 500_000_000)));
        }

        @Test
        void with_nanoseconds_precision_9() {
            assertEquals("\"1.000000123s\"", ProtobufJson.toJson(new Duration(1, 123)));
        }

        @Test
        void negative() {
            assertEquals("\"-5s\"", ProtobufJson.toJson(new Duration(-5, 0)));
        }

        @Test
        void roundtrip_milliseconds() {
            Duration d = new Duration(7, 250_000_000);
            assertEquals(d, ProtobufJson.fromJson(Duration.class, ProtobufJson.toJson(d)));
        }

        @Test
        void roundtrip_negative_nanos() {
            Duration d = new Duration(-2, -500_000_000);
            assertEquals(d, ProtobufJson.fromJson(Duration.class, ProtobufJson.toJson(d)));
        }
    }

    @Nested
    @DisplayName("Empty — {}")
    class Em {

        @Test
        void serializes_to_empty_object() {
            assertEquals("{}", ProtobufJson.toJson(Empty.INSTANCE));
        }

        @Test
        void parses_from_empty_object() {
            assertEquals(Empty.INSTANCE, ProtobufJson.fromJson(Empty.class, "{}"));
        }
    }

    @Nested
    @DisplayName("FieldMask — paths camelCase virgule-séparés")
    class Mask {

        @Test
        void emits_camelCase_paths() {
            FieldMask m = new FieldMask(List.of("user.first_name", "user.address.zip_code"));
            assertEquals("\"user.firstName,user.address.zipCode\"", ProtobufJson.toJson(m));
        }

        @Test
        void roundtrip_back_to_snake_case() {
            FieldMask original = new FieldMask(List.of("first_name", "year_of_birth"));
            String json = ProtobufJson.toJson(original);
            FieldMask back = ProtobufJson.fromJson(FieldMask.class, json);
            assertEquals(original, back);
        }

        @Test
        void empty_paths() {
            FieldMask m = new FieldMask(List.of());
            assertEquals("\"\"", ProtobufJson.toJson(m));
            assertEquals(m, ProtobufJson.fromJson(FieldMask.class, "\"\""));
        }
    }

    @Nested
    @DisplayName("Wrappers — valeur primitive directement")
    class Wrap {

        @Test
        void int32_value_is_naked_number() {
            assertEquals("42", ProtobufJson.toJson(new Wrappers.Int32Value(42)));
        }

        @Test
        void int64_value_is_naked_string() {
            assertEquals("\"9007199254740993\"",
                    ProtobufJson.toJson(new Wrappers.Int64Value(9_007_199_254_740_993L)));
        }

        @Test
        void bool_value_is_naked_bool() {
            assertEquals("true", ProtobufJson.toJson(new Wrappers.BoolValue(true)));
        }

        @Test
        void string_value_is_naked_string() {
            assertEquals("\"hello\"", ProtobufJson.toJson(new Wrappers.StringValue("hello")));
        }

        @Test
        void bytes_value_is_base64() {
            assertEquals("\"AQID\"", ProtobufJson.toJson(new Wrappers.BytesValue(new byte[]{1, 2, 3})));
        }

        @Test
        void int32_roundtrip() {
            Wrappers.Int32Value v = new Wrappers.Int32Value(7);
            assertEquals(v, ProtobufJson.fromJson(Wrappers.Int32Value.class, ProtobufJson.toJson(v)));
        }

        @Test
        void nested_in_outer_message() {
            Outer o = new Outer("x", null, null,
                    new Wrappers.Int32Value(3), new Wrappers.StringValue("alias"));
            String json = ProtobufJson.toJson(o);
            assertTrue(json.contains("\"count\":3"), "got " + json);
            assertTrue(json.contains("\"alias\":\"alias\""), "got " + json);
            Outer back = ProtobufJson.fromJson(Outer.class, json);
            assertEquals(o.count(), back.count());
            assertEquals(o.alias(), back.alias());
        }
    }
}
