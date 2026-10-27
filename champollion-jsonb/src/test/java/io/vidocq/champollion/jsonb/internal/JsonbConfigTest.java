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

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.config.PropertyOrderStrategy;
import jakarta.json.bind.config.PropertyVisibilityStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M4.7 — JsonbConfig properties")
class JsonbConfigTest {

    @Nested
    @DisplayName("JSONB_FORMATTING — pretty printing")
    class Formatting {

        record Coord(int x, int y) {}

        @Test
        void pretty_printing_off_by_default() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Coord(3, 4));
                assertEquals("{\"x\":3,\"y\":4}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void pretty_printing_on_uses_indentation() {
            var config = new JsonbConfig().withFormatting(true);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Coord(3, 4));
                // 4 espaces d'indent + LF entre chaque membre
                assertEquals("{\n    \"x\": 3,\n    \"y\": 4\n}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("JSONB_NULL_VALUES — global null inclusion")
    class NullValues {

        public static class Box {
            public String name;
            public String content;
            public Box() {}
            public Box(String n, String c) { name = n; content = c; }
        }

        @Test
        void null_values_omitted_by_default() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Box("crate", null));
                assertEquals("{\"name\":\"crate\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void null_values_included_when_config_true() {
            var config = new JsonbConfig().withNullValues(true);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Box("crate", null));
                // Both properties are included, content is null.
                assertTrue(json.contains("\"content\":null"),
                        "JSONB_NULL_VALUES=true must force inclusion. JSON = " + json);
                assertTrue(json.contains("\"name\":\"crate\""));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("JSONB_DATE_FORMAT — global date pattern")
    class DateFormat {

        record Event(String name, LocalDate when) {}

        @Test
        void global_date_format_applied_to_localdate() {
            var config = new JsonbConfig().withDateFormat("dd/MM/yyyy", null);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Event("Demo", LocalDate.of(2026, 5, 3)));
                assertEquals("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void global_date_format_used_for_read() {
            var config = new JsonbConfig().withDateFormat("dd/MM/yyyy", null);
            try (var j = JsonbBuilder.create(config)) {
                Event ev = j.fromJson("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", Event.class);
                assertEquals(LocalDate.of(2026, 5, 3), ev.when());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("JSONB_ADAPTERS — global adapters")
    class Adapters {

        public record Amount(long cents) {}

        public static class Invoice {
            public Amount total;
        }

        public static class AmountAdapter implements JsonbAdapter<Amount, String> {
            @Override public String adaptToJson(Amount amount) {
                return amount.cents() / 100 + "." + String.format("%02d", amount.cents() % 100);
            }
            @Override public Amount adaptFromJson(String text) {
                return new Amount(new java.math.BigDecimal(text).movePointRight(2).longValueExact());
            }
        }

        @Test
        void global_adapter_applies_on_write_and_read() throws Exception {
            var config = new JsonbConfig().withAdapters(new AmountAdapter());
            try (var j = JsonbBuilder.create(config)) {
                var invoice = new Invoice();
                invoice.total = new Amount(1234);
                assertEquals("{\"total\":\"12.34\"}", j.toJson(invoice));
                assertEquals(new Amount(506), j.fromJson("{\"total\":\"5.06\"}", Invoice.class).total);
            }
        }
    }

    @Nested
    @DisplayName("JSONB_PROPERTY_VISIBILITY_STRATEGY")
    class Visibility {

        public static class Mixed {
            public String visibleField = "f";
            private String hidden = "h";
            public String getHidden() { return hidden; }
            public void setHidden(String hidden) { this.hidden = hidden; }
            public String getComputed() { return "c"; }
            public void setAlias(String alias) { this.hidden = alias; }
        }

        /** Shows every field and no method: a getter only counts through its backing field. */
        public static class FieldsOnly implements PropertyVisibilityStrategy {
            @Override public boolean isVisible(Field field) { return true; }
            @Override public boolean isVisible(Method method) { return false; }
        }

        @Test
        void a_hidden_getter_stays_visible_through_its_field() throws Exception {
            // JSON-B 3.0 §4.6: a property is visible when its field OR its accessor is.
            var config = new JsonbConfig().withPropertyVisibilityStrategy(new FieldsOnly());
            try (var j = JsonbBuilder.create(config)) {
                assertEquals("{\"hidden\":\"h\",\"visibleField\":\"f\"}", j.toJson(new Mixed()));
                // "alias" has a setter but no field: hidden like the other methods.
                Mixed back = j.fromJson("{\"hidden\":\"x\",\"visibleField\":\"y\",\"computed\":\"z\",\"alias\":\"w\"}",
                        Mixed.class);
                assertEquals("x", back.getHidden());
                assertEquals("y", back.visibleField);
            }
        }
    }

    @Nested
    @DisplayName("JSONB_PROPERTY_ORDER_STRATEGY")
    class PropertyOrder {

        public static class Base {
            private final String b = "1";
            public String getB() { return b; }
        }

        public static class Derived extends Base {
            private final String a = "2";
            private final String c = "3";
            public String getA() { return a; }
            public String getC() { return c; }
        }

        @Test
        void reverse_order_sorts_each_class_level_of_getters() throws Exception {
            // JSON-B 3.0 §4.3: parent properties come first, each level sorted by the strategy.
            var config = new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.REVERSE);
            try (var j = JsonbBuilder.create(config)) {
                assertEquals("{\"b\":\"1\",\"c\":\"3\",\"a\":\"2\"}", j.toJson(new Derived()));
            }
        }
    }

    @Nested
    @DisplayName("JSONB_CREATOR_PARAMETERS_REQUIRED")
    class CreatorParametersRequired {

        public static class ByConstructor {
            final int x;
            final int y;
            @JsonbCreator
            public ByConstructor(@JsonbProperty("x") int x, @JsonbProperty("y") int y) { this.x = x; this.y = y; }
        }

        public static class ByFactory {
            final int x;
            final int y;
            private ByFactory(int x, int y) { this.x = x; this.y = y; }
            @JsonbCreator
            public static ByFactory of(@JsonbProperty("x") int x, @JsonbProperty("y") int y) { return new ByFactory(x, y); }
        }

        @Test
        void missing_creator_parameter_is_rejected_for_constructors_and_factories() throws Exception {
            var config = new JsonbConfig().withCreatorParametersRequired(true);
            try (var j = JsonbBuilder.create(config)) {
                var byConstructor = assertThrows(JsonbException.class, () -> j.fromJson("{\"x\":1}", ByConstructor.class));
                assertTrue(byConstructor.getMessage().contains("Missing required @JsonbCreator parameter"),
                        byConstructor.getMessage());
                var byFactory = assertThrows(JsonbException.class, () -> j.fromJson("{\"y\":1}", ByFactory.class));
                assertTrue(byFactory.getMessage().contains("Missing required @JsonbCreator parameter"),
                        byFactory.getMessage());
                assertEquals(2, j.fromJson("{\"x\":1,\"y\":2}", ByFactory.class).y);
            }
        }
    }
}
