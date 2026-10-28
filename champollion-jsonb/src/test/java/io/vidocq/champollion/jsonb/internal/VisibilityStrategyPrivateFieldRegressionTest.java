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
import jakarta.json.bind.config.PropertyVisibilityStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261028-04 (write side): JSON-B 3.0 §4.6.1 lets a {@link PropertyVisibilityStrategy} decide for every field,
 * so a private field without accessor becomes a property when the strategy makes it visible. The writer only
 * offered public fields and getters to the strategy.
 */
@DisplayName("BUG-20261028-04 — a visibility strategy exposes private fields without accessor")
class VisibilityStrategyPrivateFieldRegressionTest {

    /** Makes every field visible, no method. */
    public static final class AllFields implements PropertyVisibilityStrategy {
        @Override public boolean isVisible(Field field) { return true; }
        @Override public boolean isVisible(Method method) { return false; }
    }

    /** Makes every field visible except the one named {@code secret}. */
    public static final class AllFieldsButSecret implements PropertyVisibilityStrategy {
        @Override public boolean isVisible(Field field) { return !"secret".equals(field.getName()); }
        @Override public boolean isVisible(Method method) { return false; }
    }

    public static class Base {
        private String inherited = "i";

        String inherited() { return inherited; }
    }

    public static class Bean extends Base {
        private static final String CONSTANT = "c";
        private String hidden = "h";
        private String secret = "s";
        private transient String scratch = "t";
        public int open = 1;

        String hidden() { return hidden + secret + scratch + CONSTANT; }
    }

    @Test
    void a_strategy_that_shows_every_field_writes_the_private_ones() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withPropertyVisibilityStrategy(new AllFields()))) {
            assertEquals("{\"inherited\":\"i\",\"hidden\":\"h\",\"open\":1,\"secret\":\"s\"}", jsonb.toJson(new Bean()));
        }
    }

    @Test
    void the_strategy_still_hides_the_fields_it_rejects() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(
                new JsonbConfig().withPropertyVisibilityStrategy(new AllFieldsButSecret()))) {
            assertEquals("{\"inherited\":\"i\",\"hidden\":\"h\",\"open\":1}", jsonb.toJson(new Bean()));
        }
    }

    @Test
    void without_a_strategy_only_public_members_are_written() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            assertEquals("{\"open\":1}", jsonb.toJson(new Bean()));
        }
    }
}
