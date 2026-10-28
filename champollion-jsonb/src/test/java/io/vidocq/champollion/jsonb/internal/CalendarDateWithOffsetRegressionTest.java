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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261027-02: a date with an offset and no time ({@code 2020-01-02+01:00}, {@code 2020-01-02Z}) could not be
 * read into a {@link Calendar}, although it is the very form the writer uses for a calendar at midnight
 * (JSON-B 3.0 §3.5.1, {@code ISO_OFFSET_DATE}).
 */
@DisplayName("BUG-20261027-02 — Calendar read from a date with an offset only")
class CalendarDateWithOffsetRegressionTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    @Test
    void reads_a_date_with_a_numeric_offset() {
        Calendar out = jsonb.fromJson("\"2020-01-02+01:00\"", Calendar.class);
        assertEquals(Instant.parse("2020-01-01T23:00:00Z"), out.toInstant());
    }

    @Test
    void reads_a_date_in_utc() {
        Calendar out = jsonb.fromJson("\"2020-01-02Z\"", Calendar.class);
        assertEquals(Instant.parse("2020-01-02T00:00:00Z"), out.toInstant());
    }

    @Test
    void a_calendar_at_midnight_round_trips() {
        var midnight = GregorianCalendar.from(ZonedDateTime.of(2020, 1, 2, 0, 0, 0, 0, ZoneId.of("Europe/Paris")));
        String json = jsonb.toJson(midnight);
        assertEquals("\"2020-01-02+01:00\"", json);
        assertEquals(midnight.toInstant(), jsonb.fromJson(json, GregorianCalendar.class).toInstant());
    }
}
