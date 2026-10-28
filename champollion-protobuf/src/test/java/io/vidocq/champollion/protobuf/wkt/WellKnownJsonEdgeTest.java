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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufJson;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Edge cases of the JSON form of Timestamp, Duration, FieldMask and Any. */
class WellKnownJsonEdgeTest {

    private static final String ANY_URL = "type.googleapis.com/google.protobuf.Any";
    private static final String DURATION_URL = "type.googleapis.com/google.protobuf.Duration";

    @ProtobufMessage("test.AnyHolder")
    public record AnyHolder(
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Any any,
            @ProtobufField(number = 2, type = FieldType.MESSAGE) List<Timestamp> times
    ) implements Message {}

    @ParameterizedTest
    @ValueSource(strings = {
            "1970-01-01t00:00:00Z", "1970-01-01T00:00:00z", "1970-01-01", "0000-12-31T23:59:59Z"
    })
    void malformed_or_out_of_range_timestamps_are_rejected(String text) {
        String json = "\"" + text + "\"";
        assertThrows(IllegalArgumentException.class, () -> ProtobufJson.fromJson(Timestamp.class, json));
    }

    @Test
    void repeated_timestamps_are_an_array_of_strings() {
        AnyHolder h = new AnyHolder(null, List.of(new Timestamp(0, 0), new Timestamp(1, 500_000_000)));
        String json = ProtobufJson.toJson(h);
        assertEquals("{\"times\":[\"1970-01-01T00:00:00Z\",\"1970-01-01T00:00:01.500Z\"]}", json);
        assertEquals(h, ProtobufJson.fromJson(AnyHolder.class, json));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 500000000, 1.500s",
            "1, 1000, 1.000001s",
            "1, 1, 1.000000001s",
            "-1, -500000000, -1.500s",
            "0, -1, -0.000000001s",
            "7, 0, 7s"
    })
    void duration_uses_three_six_or_nine_fraction_digits(long seconds, int nanos, String expected) {
        Duration d = new Duration(seconds, nanos);
        assertEquals("\"" + expected + "\"", ProtobufJson.toJson(d));
        assertEquals(d, ProtobufJson.fromJson(Duration.class, "\"" + expected + "\""));
    }

    @Test
    void duration_fraction_beyond_nanoseconds_is_truncated() {
        assertEquals(new Duration(1, 123_456_789), ProtobufJson.fromJson(Duration.class, "\"1.1234567891s\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "\"315576000001s\"", "\"-315576000001s\""})
    void durations_without_unit_or_out_of_range_are_rejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> ProtobufJson.fromJson(Duration.class, json));
    }

    @Test
    void field_mask_paths_are_camel_case_in_json_and_snake_case_in_java() {
        FieldMask m = FieldMask.of("foo_bar.baz_qux", "x");
        assertEquals("\"fooBar.bazQux,x\"", ProtobufJson.toJson(m));
        assertEquals(m, ProtobufJson.fromJson(FieldMask.class, "\"fooBar.bazQux,x\""));
    }

    @Test
    void empty_any_forms_read_as_the_default_any() {
        Any empty = new Any("", new byte[0]);
        assertEquals(empty, ProtobufJson.fromJson(Any.class, "{}"));
        assertEquals(empty, ProtobufJson.fromJson(Any.class, "{\"@type\":\"\"}"));
        assertEquals("{}", ProtobufJson.toJson(empty));
    }

    @Test
    void a_default_any_field_is_an_empty_object() {
        AnyHolder h = new AnyHolder(new Any("", new byte[0]), List.of());
        assertEquals("{\"any\":{}}", ProtobufJson.toJson(h));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"@type\":\"\",\"x\":1}",
            "{\"@type\":\"no-slash\"}",
            "{\"@type\":\"type.googleapis.com/google.protobuf.Timestamp\"}"
    })
    void invalid_any_objects_are_rejected(String json) {
        assertThrows(UncheckedIOException.class, () -> ProtobufJson.fromJson(Any.class, json));
    }

    @Test
    void an_any_of_an_unregistered_type_keeps_its_raw_value() {
        Any withoutValue = ProtobufJson.fromJson(Any.class, "{\"@type\":\"example.com/x.Unknown\"}");
        assertEquals(0, withoutValue.value().length);
        Any notBase64 = ProtobufJson.fromJson(Any.class, "{\"@type\":\"example.com/x.Unknown\",\"value\":1}");
        assertEquals(0, notBase64.value().length);
    }

    @Test
    void an_any_of_empty_needs_no_value_member() {
        Any any = ProtobufJson.fromJson(Any.class, "{\"@type\":\"type.googleapis.com/google.protobuf.Empty\"}");
        assertEquals("type.googleapis.com/google.protobuf.Empty", any.type_url());
        assertArrayEquals(new byte[0], any.value());
    }

    @Test
    void an_any_can_wrap_another_any() {
        String json = "{\"@type\":\"" + ANY_URL + "\",\"value\":{\"@type\":\"" + DURATION_URL + "\",\"value\":\"2s\"}}";
        Any outer = ProtobufJson.fromJson(Any.class, json);
        assertEquals(ANY_URL, outer.type_url());
        assertEquals(json, ProtobufJson.toJson(outer));
    }

    @Test
    void an_any_whose_value_does_not_decode_cannot_be_written() {
        Any broken = new Any(DURATION_URL, new byte[] {0x0A, 0x05});
        assertThrows(RuntimeException.class, () -> ProtobufJson.toJson(broken));
    }
}
