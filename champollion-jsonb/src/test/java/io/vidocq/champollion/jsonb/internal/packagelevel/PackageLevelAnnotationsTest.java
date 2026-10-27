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
package io.vidocq.champollion.jsonb.internal.packagelevel;

import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("JSON-B 3.0 §4.7 / §4.9 — package-level customization")
class PackageLevelAnnotationsTest {

    public static class Measurement {
        private LocalDate when;
        private double amount;
        private String internalCode;

        public Measurement() {}

        Measurement(LocalDate when, double amount, String internalCode) {
            this.when = when;
            this.amount = amount;
            this.internalCode = internalCode;
        }

        public LocalDate getWhen() { return when; }
        public void setWhen(LocalDate when) { this.when = when; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
        public String getInternalCode() { return internalCode; }
        public void setInternalCode(String internalCode) { this.internalCode = internalCode; }
    }

    @Test
    void package_formats_and_visibility_apply_to_every_type_of_the_package() throws Exception {
        try (var j = JsonbBuilder.create()) {
            String json = j.toJson(new Measurement(LocalDate.of(2026, 5, 3), 12.5, "x-42"));
            assertEquals("{\"amount\":\"12.50\",\"when\":\"03.05.2026\"}", json);

            Measurement back = j.fromJson("{\"amount\":\"7.25\",\"when\":\"04.06.2026\",\"internalCode\":\"y\"}",
                    Measurement.class);
            assertEquals(7.25, back.getAmount());
            assertEquals(LocalDate.of(2026, 6, 4), back.getWhen());
            assertNull(back.getInternalCode());
        }
    }
}
