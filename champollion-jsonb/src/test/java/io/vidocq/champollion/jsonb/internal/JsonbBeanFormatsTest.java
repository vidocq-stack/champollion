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
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNumberFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @JsonbDateFormat} and {@code @JsonbNumberFormat} on JavaBean properties (JSON-B 3.0 §4.4), placed on the
 * setter, the getter or the field, for every supported date-like and numeric type, plus the
 * {@code JsonbConfig.DATE_FORMAT} default.
 */
@DisplayName("JSON-B — date and number formats on bean properties")
class JsonbBeanFormatsTest {

    private static final String DAY_PATTERN = "dd.MM.yyyy";
    private static final String STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String OFFSET_STAMP_PATTERN = "yyyy-MM-dd HH:mm:ssXXX";
    private static final String DAY_TEXT = "02.01.2020";
    private static final String STAMP_TEXT = "2020-01-02 03:04:05";
    private static final Instant STAMP = Instant.parse("2020-01-02T03:04:05Z");

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    /** Formats on the setters (read side) and the getters (write side). */
    public static class Dates {
        private Date date;
        private Calendar calendar;
        private LocalDate localDate;
        private LocalDateTime localDateTime;
        private OffsetDateTime offsetDateTime;
        private ZonedDateTime zonedDateTime;
        private LocalTime localTime;
        private OffsetTime offsetTime;
        private Instant instant;
        private Duration duration;
        private Period period;

        public Dates() {}

        @JsonbDateFormat(STAMP_PATTERN)
        public Date getDate() { return date; }
        @JsonbDateFormat(STAMP_PATTERN)
        public void setDate(Date date) { this.date = date; }

        @JsonbDateFormat(STAMP_PATTERN)
        public Calendar getCalendar() { return calendar; }
        @JsonbDateFormat(STAMP_PATTERN)
        public void setCalendar(Calendar calendar) { this.calendar = calendar; }

        @JsonbDateFormat(DAY_PATTERN)
        public LocalDate getLocalDate() { return localDate; }
        @JsonbDateFormat(DAY_PATTERN)
        public void setLocalDate(LocalDate localDate) { this.localDate = localDate; }

        @JsonbDateFormat(STAMP_PATTERN)
        public LocalDateTime getLocalDateTime() { return localDateTime; }
        @JsonbDateFormat(STAMP_PATTERN)
        public void setLocalDateTime(LocalDateTime localDateTime) { this.localDateTime = localDateTime; }

        @JsonbDateFormat(OFFSET_STAMP_PATTERN)
        public OffsetDateTime getOffsetDateTime() { return offsetDateTime; }
        @JsonbDateFormat(OFFSET_STAMP_PATTERN)
        public void setOffsetDateTime(OffsetDateTime offsetDateTime) { this.offsetDateTime = offsetDateTime; }

        @JsonbDateFormat("yyyy-MM-dd HH:mm:ss VV")
        public ZonedDateTime getZonedDateTime() { return zonedDateTime; }
        @JsonbDateFormat("yyyy-MM-dd HH:mm:ss VV")
        public void setZonedDateTime(ZonedDateTime zonedDateTime) { this.zonedDateTime = zonedDateTime; }

        @JsonbDateFormat("HH.mm")
        public LocalTime getLocalTime() { return localTime; }
        @JsonbDateFormat("HH.mm")
        public void setLocalTime(LocalTime localTime) { this.localTime = localTime; }

        @JsonbDateFormat("HH.mmXXX")
        public OffsetTime getOffsetTime() { return offsetTime; }
        @JsonbDateFormat("HH.mmXXX")
        public void setOffsetTime(OffsetTime offsetTime) { this.offsetTime = offsetTime; }

        @JsonbDateFormat(OFFSET_STAMP_PATTERN)
        public Instant getInstant() { return instant; }
        @JsonbDateFormat(OFFSET_STAMP_PATTERN)
        public void setInstant(Instant instant) { this.instant = instant; }

        @JsonbDateFormat("ignored")
        public Duration getDuration() { return duration; }
        @JsonbDateFormat("ignored")
        public void setDuration(Duration duration) { this.duration = duration; }

        public Period getPeriod() { return period; }
        public void setPeriod(Period period) { this.period = period; }
    }

    /** The default pattern ({@code ##default}) on the getters only: ISO forms, read through the setter lookup. */
    public static class DefaultPatternDates {
        private Date date;
        private Calendar calendar;
        private LocalDate localDate;
        private LocalDateTime localDateTime;
        private OffsetDateTime offsetDateTime;
        private ZonedDateTime zonedDateTime;
        private LocalTime localTime;
        private OffsetTime offsetTime;
        private Instant instant;
        private Duration duration;
        private Period period;

        public DefaultPatternDates() {}

        @JsonbDateFormat
        public Date getDate() { return date; }
        public void setDate(Date date) { this.date = date; }
        @JsonbDateFormat
        public Calendar getCalendar() { return calendar; }
        public void setCalendar(Calendar calendar) { this.calendar = calendar; }
        @JsonbDateFormat
        public LocalDate getLocalDate() { return localDate; }
        public void setLocalDate(LocalDate localDate) { this.localDate = localDate; }
        @JsonbDateFormat
        public LocalDateTime getLocalDateTime() { return localDateTime; }
        public void setLocalDateTime(LocalDateTime localDateTime) { this.localDateTime = localDateTime; }
        @JsonbDateFormat
        public OffsetDateTime getOffsetDateTime() { return offsetDateTime; }
        public void setOffsetDateTime(OffsetDateTime offsetDateTime) { this.offsetDateTime = offsetDateTime; }
        @JsonbDateFormat
        public ZonedDateTime getZonedDateTime() { return zonedDateTime; }
        public void setZonedDateTime(ZonedDateTime zonedDateTime) { this.zonedDateTime = zonedDateTime; }
        @JsonbDateFormat
        public LocalTime getLocalTime() { return localTime; }
        public void setLocalTime(LocalTime localTime) { this.localTime = localTime; }
        @JsonbDateFormat
        public OffsetTime getOffsetTime() { return offsetTime; }
        public void setOffsetTime(OffsetTime offsetTime) { this.offsetTime = offsetTime; }
        @JsonbDateFormat
        public Instant getInstant() { return instant; }
        public void setInstant(Instant instant) { this.instant = instant; }
        @JsonbDateFormat
        public Duration getDuration() { return duration; }
        public void setDuration(Duration duration) { this.duration = duration; }
        @JsonbDateFormat
        public Period getPeriod() { return period; }
        public void setPeriod(Period period) { this.period = period; }
    }

    /** Formats on the fields only. */
    public static class FieldFormats {
        @JsonbDateFormat(DAY_PATTERN)
        private LocalDate day;
        @JsonbNumberFormat("#0.00")
        private double amount;
        @JsonbDateFormat(value = "yyyy-MM-dd'T'HH:mmx", locale = "fr-FR")
        private Date stamp;

        public FieldFormats() {}

        public LocalDate getDay() { return day; }
        public void setDay(LocalDate day) { this.day = day; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
        public Date getStamp() { return stamp; }
        public void setStamp(Date stamp) { this.stamp = stamp; }
    }

    /** Number formats on setters for every numeric target type. */
    public static class Numbers {
        private int i;
        private long l;
        private Double d;
        private float f;
        private short s;
        private byte b;
        private BigDecimal bd;
        private BigInteger bi;

        public Numbers() {}

        public int getI() { return i; }
        @JsonbNumberFormat("#,##0")
        public void setI(int i) { this.i = i; }
        public long getL() { return l; }
        @JsonbNumberFormat("#,##0")
        public void setL(long l) { this.l = l; }
        @JsonbNumberFormat("0.0#")
        public Double getD() { return d; }
        public void setD(Double d) { this.d = d; }
        public float getF() { return f; }
        @JsonbNumberFormat("0.0")
        public void setF(float f) { this.f = f; }
        public short getS() { return s; }
        @JsonbNumberFormat("0")
        public void setS(short s) { this.s = s; }
        public byte getB() { return b; }
        @JsonbNumberFormat("0")
        public void setB(byte b) { this.b = b; }
        @JsonbNumberFormat(value = "#,##0.00", locale = "de-DE")
        public BigDecimal getBd() { return bd; }
        @JsonbNumberFormat(value = "#,##0.00", locale = "de-DE")
        public void setBd(BigDecimal bd) { this.bd = bd; }
        public BigInteger getBi() { return bi; }
        @JsonbNumberFormat
        public void setBi(BigInteger bi) { this.bi = bi; }
    }

    /** A class-level number format, applied to every numeric property. */
    @JsonbNumberFormat("000")
    public static class ClassNumberFormat {
        private int code;

        public ClassNumberFormat() {}

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
    }

    /** A bean without formats, for the {@code JsonbConfig.DATE_FORMAT} default. */
    public static class Plain {
        private LocalDate day;
        private Date date;

        public Plain() {}

        public LocalDate getDay() { return day; }
        public void setDay(LocalDate day) { this.day = day; }
        public Date getDate() { return date; }
        public void setDate(Date date) { this.date = date; }
    }

    private static Calendar utcCalendar(Instant instant) {
        var cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.setTime(Date.from(instant));
        return cal;
    }

    @Test
    void custom_patterns_round_trip() {
        var in = new Dates();
        in.setDate(Date.from(STAMP));
        in.setCalendar(utcCalendar(STAMP));
        in.setLocalDate(LocalDate.of(2020, 1, 2));
        in.setLocalDateTime(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        in.setOffsetDateTime(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)));
        in.setZonedDateTime(ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneId.of("Europe/Paris")));
        in.setLocalTime(LocalTime.of(3, 4));
        in.setOffsetTime(OffsetTime.of(3, 4, 0, 0, ZoneOffset.ofHours(1)));
        in.setInstant(STAMP);
        in.setDuration(Duration.ofSeconds(61));
        in.setPeriod(Period.ofDays(3));
        String json = jsonb.toJson(in);
        assertEquals("{\"calendar\":\"" + STAMP_TEXT + "\",\"date\":\"" + STAMP_TEXT + "\",\"duration\":\"PT1M1S\","
                + "\"instant\":\"2020-01-02 03:04:05Z\",\"localDate\":\"" + DAY_TEXT + "\","
                + "\"localDateTime\":\"" + STAMP_TEXT + "\",\"localTime\":\"03.04\","
                + "\"offsetDateTime\":\"2020-01-02 03:04:05+02:00\",\"offsetTime\":\"03.04+01:00\","
                + "\"period\":\"P3D\",\"zonedDateTime\":\"2020-01-02 03:04:05 Europe/Paris\"}", json);
        var out = jsonb.fromJson(json, Dates.class);
        assertAll(
                () -> assertEquals(in.getDate(), out.getDate()),
                () -> assertEquals(in.getLocalDate(), out.getLocalDate()),
                () -> assertEquals(in.getLocalDateTime(), out.getLocalDateTime()),
                () -> assertEquals(in.getOffsetDateTime(), out.getOffsetDateTime()),
                () -> assertEquals(in.getZonedDateTime(), out.getZonedDateTime()),
                () -> assertEquals(in.getLocalTime(), out.getLocalTime()),
                () -> assertEquals(in.getOffsetTime(), out.getOffsetTime()),
                () -> assertEquals(in.getInstant(), out.getInstant()),
                () -> assertEquals(in.getDuration(), out.getDuration()),
                () -> assertEquals(in.getPeriod(), out.getPeriod()));
    }

    @Test
    void custom_patterns_read_null() {
        String json = "{\"date\":null,\"calendar\":null,\"localDate\":null,\"duration\":null}";
        var out = jsonb.fromJson(json, Dates.class);
        assertNull(out.getDate());
        assertNull(out.getCalendar());
        assertNull(out.getLocalDate());
        assertNull(out.getDuration());
    }

    @Test
    void custom_patterns_skip_null_members_on_write() {
        assertEquals("{}", jsonb.toJson(new Dates()));
    }

    @Test
    void a_date_that_does_not_match_its_pattern_is_rejected() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"date\":\"2020/01/02\"}", Dates.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"calendar\":\"2020/01/02\"}", Dates.class));
    }

    @Test
    void default_pattern_round_trip() {
        var in = new DefaultPatternDates();
        in.setDate(Date.from(STAMP));
        in.setCalendar(GregorianCalendar.from(ZonedDateTime.ofInstant(STAMP, ZoneId.of("UTC"))));
        in.setLocalDate(LocalDate.of(2020, 1, 2));
        in.setLocalDateTime(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        in.setOffsetDateTime(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)));
        in.setZonedDateTime(ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneId.of("Europe/Paris")));
        in.setLocalTime(LocalTime.of(3, 4, 5));
        in.setOffsetTime(OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(1)));
        in.setInstant(STAMP);
        in.setDuration(Duration.ofHours(2));
        in.setPeriod(Period.ofMonths(4));
        String json = jsonb.toJson(in);
        assertAll(
                () -> assertTrue(json.contains("\"date\":\"2020-01-02T03:04:05Z[UTC]\""), json),
                () -> assertTrue(json.contains("\"calendar\":\"2020-01-02T03:04:05Z[UTC]\""), json),
                () -> assertTrue(json.contains("\"localDate\":\"2020-01-02\""), json),
                () -> assertTrue(json.contains("\"instant\":\"2020-01-02T03:04:05Z\""), json),
                () -> assertTrue(json.contains("\"duration\":\"PT2H\""), json),
                () -> assertTrue(json.contains("\"period\":\"P4M\""), json));
        var out = jsonb.fromJson(json, DefaultPatternDates.class);
        assertAll(
                () -> assertEquals(in.getDate(), out.getDate()),
                () -> assertEquals(in.getCalendar().toInstant(), out.getCalendar().toInstant()),
                () -> assertEquals(in.getLocalDate(), out.getLocalDate()),
                () -> assertEquals(in.getLocalDateTime(), out.getLocalDateTime()),
                () -> assertEquals(in.getOffsetDateTime(), out.getOffsetDateTime()),
                () -> assertEquals(in.getZonedDateTime(), out.getZonedDateTime()),
                () -> assertEquals(in.getLocalTime(), out.getLocalTime()),
                () -> assertEquals(in.getOffsetTime(), out.getOffsetTime()),
                () -> assertEquals(in.getInstant(), out.getInstant()),
                () -> assertEquals(in.getDuration(), out.getDuration()),
                () -> assertEquals(in.getPeriod(), out.getPeriod()));
    }

    @Test
    void default_pattern_reads_null() {
        var out = jsonb.fromJson("{\"date\":null,\"calendar\":null,\"instant\":null}", DefaultPatternDates.class);
        assertNull(out.getDate());
        assertNull(out.getCalendar());
        assertNull(out.getInstant());
    }

    @Test
    void formats_on_the_fields() {
        var in = new FieldFormats();
        in.setDay(LocalDate.of(2020, 1, 2));
        in.setAmount(3.5);
        in.setStamp(Date.from(Instant.parse("2020-01-02T03:04:00Z")));
        String json = jsonb.toJson(in);
        assertEquals("{\"amount\":\"3.50\",\"day\":\"" + DAY_TEXT + "\",\"stamp\":\"2020-01-02T03:04+00\"}", json);
        var out = jsonb.fromJson("{\"amount\":\"3.50\",\"day\":\"" + DAY_TEXT + "\"}", FieldFormats.class);
        assertEquals(3.5, out.getAmount());
        assertEquals(in.getDay(), out.getDay());
    }

    @Test
    void number_formats_on_setters_convert_to_the_target_type() {
        String json = "{\"i\":\"1,234\",\"l\":\"5,000,000\",\"d\":\"2.5\",\"f\":\"1.5\",\"s\":\"7\",\"b\":\"8\","
                + "\"bd\":\"1.234,50\",\"bi\":\"12\"}";
        var out = jsonb.fromJson(json, Numbers.class);
        assertAll(
                () -> assertEquals(1234, out.getI()),
                () -> assertEquals(5_000_000L, out.getL()),
                () -> assertEquals(2.5, out.getD()),
                () -> assertEquals(1.5f, out.getF()),
                () -> assertEquals((short) 7, out.getS()),
                () -> assertEquals((byte) 8, out.getB()),
                () -> assertEquals(new BigDecimal("1234.5"), out.getBd()),
                () -> assertEquals(BigInteger.valueOf(12), out.getBi()));
    }

    @Test
    void number_formats_on_getters_write_text() {
        var in = new Numbers();
        in.setD(2.0);
        in.setBd(new BigDecimal("1234.5"));
        String json = jsonb.toJson(in);
        assertTrue(json.contains("\"d\":\"2.0\""), json);
        assertTrue(json.contains("\"bd\":\"1.234,50\""), json);
    }

    @Test
    void a_null_formatted_number_is_read_as_null() {
        assertNull(jsonb.fromJson("{\"bd\":null}", Numbers.class).getBd());
    }

    @Test
    void a_number_that_does_not_match_its_format_is_rejected() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"i\":\"abc\"}", Numbers.class));
    }

    @Test
    void class_level_number_format() {
        var in = new ClassNumberFormat();
        in.setCode(7);
        assertEquals("{\"code\":\"007\"}", jsonb.toJson(in));
        assertEquals(42, jsonb.fromJson("{\"code\":\"042\"}", ClassNumberFormat.class).getCode());
    }

    @Test
    void config_date_format_applies_to_bean_properties() throws Exception {
        var config = new JsonbConfig().withDateFormat(DAY_PATTERN, Locale.FRANCE);
        try (var formatted = JsonbBuilder.create(config)) {
            var in = new Plain();
            in.setDay(LocalDate.of(2020, 1, 2));
            in.setDate(Date.from(Instant.parse("2020-01-02T00:00:00Z")));
            String json = formatted.toJson(in);
            assertEquals("{\"date\":\"" + DAY_TEXT + "\",\"day\":\"" + DAY_TEXT + "\"}", json);
            var out = formatted.fromJson(json, Plain.class);
            assertEquals(in.getDay(), out.getDay());
            assertEquals(in.getDate(), out.getDate());
        }
    }
}
