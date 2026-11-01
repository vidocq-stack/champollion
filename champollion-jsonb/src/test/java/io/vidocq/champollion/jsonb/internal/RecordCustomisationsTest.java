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
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbNumberFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A record component is a property like any other (JSON-B 3.0 §4.7): the formats and the nillability declared on the
 * record type or its package apply to it, and {@code @JsonbNumberFormat} and the locale of a format do too, on write
 * and on read (BUG-20261101-06).
 */
class RecordCustomisationsTest {

    @JsonbDateFormat("dd/MM/yyyy")
    public record Dated(LocalDate on, String label) {}

    public record Priced(@JsonbNumberFormat("#0.00") BigDecimal amount, @JsonbNumberFormat("#,##0") int count) {}

    public record French(@JsonbDateFormat(value = "d MMMM yyyy", locale = "fr") LocalDate on) {}

    @JsonbNillable
    public record Nillable(String a, String b) {}

    @JsonbNumberFormat("#0.0")
    public record TypeNumber(double ratio) {}

    @jakarta.json.bind.annotation.JsonbPropertyOrder({"c", "a"})
    public record Ordered(String a, String b, String c) {}

    public static class Counted {
        private double value;

        @JsonbNumberFormat("#,##0.00")
        public double getValue() { return value; }

        @JsonbNumberFormat("#,##0.00")
        public void setValue(double value) { this.value = value; }
    }

    public static class Fields {
        @JsonbDateFormat("dd/MM/yyyy")
        public LocalDate on;
        @JsonbNumberFormat("#,##0")
        public long count;
    }

    private static Jsonb jsonb;

    @BeforeAll
    static void create() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    @Test
    void aDateFormatOnTheRecordTypeAppliesToItsComponents() {
        var dated = new Dated(LocalDate.of(1862, 4, 3), "x");
        String json = jsonb.toJson(dated);
        assertEquals("{\"on\":\"03/04/1862\",\"label\":\"x\"}", json);
        assertEquals(dated, jsonb.fromJson(json, Dated.class));
    }

    @Test
    void aNumberFormatOnAComponentIsWrittenAndRead() {
        var priced = new Priced(new BigDecimal("1.5"), 1234);
        String json = jsonb.toJson(priced);
        assertEquals("{\"amount\":\"1.50\",\"count\":\"1,234\"}", json);
        assertEquals(priced, jsonb.fromJson(json, Priced.class));
    }

    /** A shared {@code DecimalFormat} mixes up concurrent calls (BUG-20261101-07): each call formats on its own. */
    @Test
    void aNumberFormatIsSafeAcrossThreads() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2000; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    var bean = new Counted();
                    bean.setValue(n * 1000.5);
                    String json = jsonb.toJson(bean);
                    return json.equals(String.format(java.util.Locale.ROOT, "{\"value\":\"%,.2f\"}", n * 1000.5))
                            && jsonb.fromJson(json, Counted.class).getValue() == n * 1000.5;
                }));
            }
            for (var r : results) assertEquals(Boolean.TRUE, r.get());
        }
    }

    @Test
    void aNumberFormatOnTheRecordTypeAppliesToItsComponents() {
        String json = jsonb.toJson(new TypeNumber(0.25));
        assertEquals("{\"ratio\":\"0.2\"}", json);
        assertEquals(new TypeNumber(0.5), jsonb.fromJson("{\"ratio\":\"0.5\"}", TypeNumber.class));
    }

    @Test
    void theLocaleOfADateFormatIsUsedOnWriteAndRead() {
        var french = new French(LocalDate.of(1862, 4, 3));
        String json = jsonb.toJson(french);
        assertEquals("{\"on\":\"3 avril 1862\"}", json);
        assertEquals(french, jsonb.fromJson(json, French.class));
    }

    /** A public field is read with the format it is written with. */
    @Test
    void theFormatsOfAPublicFieldApplyOnRead() {
        var fields = jsonb.fromJson("{\"on\":\"03/04/1862\",\"count\":\"1,234\"}", Fields.class);
        assertEquals(LocalDate.of(1862, 4, 3), fields.on);
        assertEquals(1234L, fields.count);
    }

    /** {@code @JsonbPropertyOrder} applies to a record: the listed components first, the others in their order. */
    @Test
    void aPropertyOrderOnARecordPutsItsComponentsFirst() {
        assertEquals("{\"c\":\"C\",\"a\":\"A\",\"b\":\"B\"}", jsonb.toJson(new Ordered("A", "B", "C")));
    }

    @Test
    void nillableOnTheRecordTypeWritesItsNullComponents() {
        assertEquals("{\"a\":\"x\",\"b\":null}", jsonb.toJson(new Nillable("x", null)));
    }
}
