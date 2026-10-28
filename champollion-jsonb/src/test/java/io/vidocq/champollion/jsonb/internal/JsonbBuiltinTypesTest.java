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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every built-in type of the runtime registries (JSON-B 3.0 §3.3 to §3.6), written and read as members of a
 * record, present and explicitly {@code null}.
 */
@DisplayName("JSON-B — built-in types as record members")
class JsonbBuiltinTypesTest {

    private static final String UTC = "UTC";

    private static final String STAMP = "2020-01-02T03:04:05Z";
    private static final String PARIS = "Europe/Paris";
    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public record Scalars(String s, Integer i, Long l, Double d, Float f, Short sh, Byte b, Boolean bo,
                          Character c, BigDecimal bd, BigInteger bi, UUID uuid, URI uri, URL url) {}

    public record Primitives(int i, long l, double d, float f, short sh, byte b, boolean bo, char c) {}

    public record Temporals(Instant instant, LocalDate date, LocalDateTime dateTime, OffsetDateTime offsetDateTime,
                            ZonedDateTime zoned, LocalTime time, OffsetTime offsetTime, Duration duration,
                            Period period, MonthDay monthDay, YearMonth yearMonth, Year year,
                            ZoneOffset offset, ZoneId zone) {}

    public record LegacyDates(Date date, Calendar calendar, GregorianCalendar gregorian, TimeZone timeZone,
                              SimpleTimeZone simpleTimeZone) {}

    public record Optionals(OptionalInt oi, OptionalLong ol, OptionalDouble od) {}

    public record JsonpValues(JsonObject object, JsonArray array, JsonString string, JsonNumber number,
                              JsonValue value, JsonStructure structure) {}

    public record WithPath(Path path) {}

    @Test
    void scalars_round_trip() throws Exception {
        var in = new Scalars("x", 1, 2L, 3.5, 4.5f, (short) 5, (byte) 6, true, 'z',
                new BigDecimal("7.25"), new BigInteger("123456789012345678901234567890"),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                URI.create("urn:isbn:0451450523"), URI.create("https://example.com/a").toURL());
        String json = jsonb.toJson(in);
        assertEquals("{\"s\":\"x\",\"i\":1,\"l\":2,\"d\":3.5,\"f\":4.5,\"sh\":5,\"b\":6,\"bo\":true,\"c\":\"z\","
                + "\"bd\":7.25,\"bi\":123456789012345678901234567890,"
                + "\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\",\"uri\":\"urn:isbn:0451450523\","
                + "\"url\":\"https://example.com/a\"}", json);
        assertEquals(in, jsonb.fromJson(json, Scalars.class));
    }

    @Test
    void scalars_read_explicit_nulls() {
        String json = "{\"s\":null,\"i\":null,\"l\":null,\"d\":null,\"f\":null,\"sh\":null,\"b\":null,\"bo\":null,"
                + "\"c\":null,\"bd\":null,\"bi\":null,\"uuid\":null,\"uri\":null,\"url\":null}";
        var out = jsonb.fromJson(json, Scalars.class);
        assertEquals(new Scalars(null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                out);
    }

    @Test
    void empty_string_reads_as_nul_character() {
        assertEquals(new Primitives(0, 0, 0, 0, (short) 0, (byte) 0, false, '\0'),
                jsonb.fromJson("{\"c\":\"\"}", Primitives.class));
    }

    @Test
    void primitives_round_trip() {
        var in = new Primitives(1, 2L, 3.25, 4.5f, (short) 5, (byte) -6, true, 'q');
        String json = jsonb.toJson(in);
        assertEquals("{\"i\":1,\"l\":2,\"d\":3.25,\"f\":4.5,\"sh\":5,\"b\":-6,\"bo\":true,\"c\":\"q\"}", json);
        assertEquals(in, jsonb.fromJson(json, Primitives.class));
    }

    @Test
    void boolean_member_rejects_a_number() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"bo\":1}", Scalars.class));
    }

    @Test
    void malformed_url_is_a_jsonb_exception() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"url\":\"no-scheme\"}", Scalars.class));
    }

    @Test
    void temporals_round_trip() {
        var in = new Temporals(
                Instant.parse(STAMP),
                LocalDate.of(2020, 1, 2),
                LocalDateTime.of(2020, 1, 2, 3, 4, 5),
                OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)),
                ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneId.of(PARIS)),
                LocalTime.of(3, 4, 5),
                OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(-1)),
                Duration.ofMinutes(90),
                Period.of(1, 2, 3),
                MonthDay.of(12, 25),
                YearMonth.of(2020, 2),
                Year.of(1999),
                ZoneOffset.ofHours(5),
                ZoneId.of("America/New_York"));
        String json = jsonb.toJson(in);
        assertAll(
                () -> assertTrue(json.contains("\"duration\":\"PT1H30M\""), json),
                () -> assertTrue(json.contains("\"period\":\"P1Y2M3D\""), json),
                () -> assertTrue(json.contains("\"monthDay\":\"--12-25\""), json),
                () -> assertTrue(json.contains("\"yearMonth\":\"2020-02\""), json),
                () -> assertTrue(json.contains("\"year\":\"1999\""), json),
                () -> assertTrue(json.contains("\"offset\":\"+05:00\""), json),
                () -> assertTrue(json.contains("\"zone\":\"America/New_York\""), json),
                () -> assertTrue(json.contains("\"offsetTime\":\"03:04:05-01:00\""), json));
        assertEquals(in, jsonb.fromJson(json, Temporals.class));
    }

    @Test
    void temporals_read_explicit_nulls() {
        String json = "{\"instant\":null,\"date\":null,\"dateTime\":null,\"offsetDateTime\":null,\"zoned\":null,"
                + "\"time\":null,\"offsetTime\":null,\"duration\":null,\"period\":null,\"monthDay\":null,"
                + "\"yearMonth\":null,\"year\":null,\"offset\":null,\"zone\":null}";
        assertEquals(new Temporals(null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                jsonb.fromJson(json, Temporals.class));
    }

    @Test
    void legacy_dates_round_trip() {
        var date = Date.from(Instant.parse(STAMP));
        var calendar = GregorianCalendar.from(ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneId.of(UTC)));
        var midnight = GregorianCalendar.from(ZonedDateTime.of(2020, 1, 2, 0, 0, 0, 0, ZoneId.of(UTC)));
        var in = new LegacyDates(date, calendar, midnight, TimeZone.getTimeZone(PARIS),
                new SimpleTimeZone(3_600_000, PARIS));
        String json = jsonb.toJson(in);
        assertAll(
                () -> assertTrue(json.contains("\"date\":\"2020-01-02T03:04:05Z[UTC]\""), json),
                () -> assertTrue(json.contains("\"calendar\":\"2020-01-02T03:04:05Z[UTC]\""), json),
                () -> assertTrue(json.contains("\"gregorian\":\"2020-01-02Z\""), json),
                () -> assertTrue(json.contains("\"timeZone\":\"Europe/Paris\""), json),
                () -> assertTrue(json.contains("\"simpleTimeZone\":\"Europe/Paris\""), json));
        var out = jsonb.fromJson(json, LegacyDates.class);
        assertEquals(date, out.date());
        assertEquals(calendar.toInstant(), out.calendar().toInstant());
        assertEquals(midnight.toInstant(), out.gregorian().toInstant());
        assertEquals(PARIS, out.timeZone().getID());
        assertEquals(3_600_000, out.simpleTimeZone().getRawOffset());
    }

    @Test
    void legacy_dates_accept_the_other_iso_forms() {
        String json = "{\"date\":\"2020-01-02T03:04:05+01:00\",\"calendar\":\"2020-01-02T03:04:05\","
                + "\"gregorian\":\"2020-01-02\"}";
        var out = jsonb.fromJson(json, LegacyDates.class);
        assertEquals(Instant.parse("2020-01-02T02:04:05Z"), out.date().toInstant());
        assertEquals(Instant.parse(STAMP), out.calendar().toInstant());
        assertEquals(Instant.parse("2020-01-02T00:00:00Z"), out.gregorian().toInstant());
    }

    @Test
    void legacy_date_accepts_local_forms() {
        assertEquals(Instant.parse(STAMP),
                jsonb.fromJson("{\"date\":\"2020-01-02T03:04:05\"}", LegacyDates.class).date().toInstant());
        assertEquals(Instant.parse("2020-01-02T00:00:00Z"),
                jsonb.fromJson("{\"date\":\"2020-01-02\"}", LegacyDates.class).date().toInstant());
    }

    @Test
    void legacy_dates_read_explicit_nulls() {
        String json = "{\"date\":null,\"calendar\":null,\"gregorian\":null,\"timeZone\":null,\"simpleTimeZone\":null}";
        assertEquals(new LegacyDates(null, null, null, null, null), jsonb.fromJson(json, LegacyDates.class));
    }

    @Test
    void deprecated_three_letter_zone_ids_are_rejected() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"timeZone\":\"PST\"}", LegacyDates.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"simpleTimeZone\":\"EST\"}", LegacyDates.class));
    }

    @Test
    void optional_primitives_round_trip() {
        var in = new Optionals(OptionalInt.of(1), OptionalLong.of(2L), OptionalDouble.of(3.5));
        String json = jsonb.toJson(in);
        assertEquals("{\"oi\":1,\"ol\":2,\"od\":3.5}", json);
        assertEquals(in, jsonb.fromJson(json, Optionals.class));
    }

    @Test
    void empty_optional_primitives_are_omitted_and_read_back_empty() {
        var empty = new Optionals(OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty());
        assertEquals("{}", jsonb.toJson(empty));
        assertEquals(empty, jsonb.fromJson("{\"oi\":null,\"ol\":null,\"od\":null}", Optionals.class));
    }

    @Test
    void optional_primitives_at_the_root() {
        assertEquals("1", jsonb.toJson(OptionalInt.of(1)));
        assertEquals("null", jsonb.toJson(OptionalLong.empty()));
        assertEquals("null", jsonb.toJson(OptionalDouble.empty()));
        assertEquals("null", jsonb.toJson(OptionalInt.empty()));
        assertEquals("2", jsonb.toJson(OptionalLong.of(2)));
        assertEquals("2.5", jsonb.toJson(OptionalDouble.of(2.5)));
    }

    @Test
    void jsonp_values_round_trip() {
        var obj = Json.createObjectBuilder().add("k", 1).build();
        var arr = Json.createArrayBuilder().add("a").add(2).build();
        var in = new JsonpValues(obj, arr, Json.createValue("s"), Json.createValue(42), JsonValue.TRUE, arr);
        String json = jsonb.toJson(in);
        assertEquals("{\"object\":{\"k\":1},\"array\":[\"a\",2],\"string\":\"s\",\"number\":42,"
                + "\"value\":true,\"structure\":[\"a\",2]}", json);
        assertEquals(in, jsonb.fromJson(json, JsonpValues.class));
    }

    @Test
    void jsonp_values_read_explicit_nulls() {
        // A null JsonStructure member is BUG-20261028-01 (still open), hence absent from this document.
        String json = "{\"object\":null,\"array\":null,\"string\":null,\"number\":null,\"value\":null}";
        var out = jsonb.fromJson(json, JsonpValues.class);
        assertNull(out.object());
        assertNull(out.array());
        assertNull(out.string());
        assertNull(out.number());
        assertEquals(JsonValue.NULL, out.value());
        assertNull(out.structure());
    }

    @Test
    void jsonp_object_member_rejects_an_array() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"object\":[]}", JsonpValues.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"array\":{}}", JsonpValues.class));
    }

    @Test
    void path_is_written_as_its_string_form() {
        assertEquals("{\"path\":\"a/b\"}", jsonb.toJson(new WithPath(Path.of("a", "b"))));
    }

    @Test
    void number_root_reads_numbers_strings_and_null() {
        assertEquals(new BigDecimal("1.5"), jsonb.fromJson("1.5", Number.class));
        assertEquals(new BigDecimal("2"), jsonb.fromJson("\"2\"", Number.class));
        assertNull(jsonb.fromJson("null", Number.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("true", Number.class));
    }

    @Test
    void number_root_writes_the_runtime_value() {
        assertEquals("3", jsonb.toJson(3, Number.class));
        assertFalse(jsonb.toJson(new BigDecimal("1.25"), Number.class).isEmpty());
    }
}
