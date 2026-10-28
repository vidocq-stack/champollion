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
import jakarta.json.bind.annotation.JsonbDateFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Period;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261028-03: a {@link Duration} or {@link Period} bean property is always written in its ISO 8601 form, a date
 * pattern does not apply to it; the reader applied the pattern anyway, so with {@code JsonbConfig.DATE_FORMAT} or a
 * {@code @JsonbDateFormat} on the property the value written could not be read back.
 */
@DisplayName("BUG-20261028-03 — Duration and Period properties under a date pattern")
class DurationWithDateFormatRegressionTest {

    private static final String PATTERN = "dd.MM.yyyy";
    private static final String JSON = "{\"duration\":\"PT1M1S\",\"period\":\"P3D\"}";

    public static class Spans {
        private Duration duration;
        private Period period;

        public Spans() {
            // JSON-B instantiates the bean through its public no-argument constructor
        }

        public Duration getDuration() { return duration; }
        public void setDuration(Duration duration) { this.duration = duration; }
        public Period getPeriod() { return period; }
        public void setPeriod(Period period) { this.period = period; }
    }

    public static class AnnotatedSpans {
        private Duration duration;
        private Period period;

        public AnnotatedSpans() {
            // JSON-B instantiates the bean through its public no-argument constructor
        }

        @JsonbDateFormat(PATTERN)
        public Duration getDuration() { return duration; }
        @JsonbDateFormat(PATTERN)
        public void setDuration(Duration duration) { this.duration = duration; }
        @JsonbDateFormat(PATTERN)
        public Period getPeriod() { return period; }
        @JsonbDateFormat(PATTERN)
        public void setPeriod(Period period) { this.period = period; }
    }

    @Test
    void the_config_date_format_leaves_durations_and_periods_iso() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withDateFormat(PATTERN, null))) {
            var in = new Spans();
            in.setDuration(Duration.ofSeconds(61));
            in.setPeriod(Period.ofDays(3));
            assertEquals(JSON, jsonb.toJson(in));
            var out = jsonb.fromJson(JSON, Spans.class);
            assertEquals(in.getDuration(), out.getDuration());
            assertEquals(in.getPeriod(), out.getPeriod());
        }
    }

    @Test
    void a_property_date_format_leaves_durations_and_periods_iso() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            var in = new AnnotatedSpans();
            in.setDuration(Duration.ofSeconds(61));
            in.setPeriod(Period.ofDays(3));
            assertEquals(JSON, jsonb.toJson(in));
            var out = jsonb.fromJson(JSON, AnnotatedSpans.class);
            assertEquals(in.getDuration(), out.getDuration());
            assertEquals(in.getPeriod(), out.getPeriod());
        }
    }
}
