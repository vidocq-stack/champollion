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
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTransient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("M4.4 — JSON-B customization (runtime)")
class JsonbCustomizationTest {

    @Nested
    @DisplayName("@JsonbProperty(name)")
    class PropertyRenaming {

        record Snake(@JsonbProperty("user_name") String userName, @JsonbProperty("age_years") int age) {}

        @Test
        void writes_renamed_members() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Snake("alice", 30));
                assertEquals("{\"user_name\":\"alice\",\"age_years\":30}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_renamed_members() {
            try (var j = JsonbBuilder.create()) {
                Snake s = j.fromJson("{\"user_name\":\"bob\",\"age_years\":25}", Snake.class);
                assertEquals(new Snake("bob", 25), s);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void original_name_is_ignored_when_property_renamed() {
            // userName ne doit PAS apparaître dans le JSON ; seulement user_name
            try (var j = JsonbBuilder.create()) {
                Snake s = j.fromJson("{\"userName\":\"bob\",\"user_name\":\"alice\",\"age_years\":30}", Snake.class);
                assertEquals(new Snake("alice", 30), s,
                        "Reader doit utiliser le nom annoté, pas le nom du composant.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbTransient — exclusion")
    class TransientExclusion {

        public static final class Account {
            public String username;
            @JsonbTransient public String password;

            public Account() {}
            public Account(String u, String p) { this.username = u; this.password = p; }
        }

        @Test
        void transient_field_excluded_from_write() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Account("alice", "s3cret"));
                // password absent
                assertEquals("{\"username\":\"alice\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void transient_field_ignored_on_read() {
            try (var j = JsonbBuilder.create()) {
                Account a = j.fromJson("{\"username\":\"bob\",\"password\":\"s3cret\"}", Account.class);
                assertEquals("bob", a.username);
                assertNull(a.password, "Champ @JsonbTransient ne doit pas être affecté par la lecture.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbNillable — force-include null members")
    class NillableInclusion {

        record Doc(String title, @JsonbNillable String description) {}

        @Test
        void nillable_member_serialized_as_null_when_null() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Doc("Champollion", null));
                // description doit apparaître avec null, pas être omise.
                assertEquals("{\"title\":\"Champollion\",\"description\":null}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void non_nillable_null_remains_omitted() {
            // title without @JsonbNillable, its null remains omitted.
            record Plain(String title, String desc) {}
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Plain(null, null));
                assertEquals("{}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbDateFormat — custom date pattern")
    class DateFormat {

        record Event(String name, @JsonbDateFormat("dd/MM/yyyy") LocalDate when) {}

        @Test
        void writes_date_with_custom_pattern() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Event("Demo", LocalDate.of(2026, 5, 3)));
                assertEquals("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_date_with_custom_pattern() {
            try (var j = JsonbBuilder.create()) {
                Event ev = j.fromJson("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", Event.class);
                assertEquals(LocalDate.of(2026, 5, 3), ev.when());
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        record Meeting(@JsonbDateFormat("yyyy-MM-dd HH:mm") LocalDateTime ts) {}

        @Test
        void writes_localdatetime_with_custom_pattern() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Meeting(LocalDateTime.of(2026, 5, 3, 10, 30)));
                assertEquals("{\"ts\":\"2026-05-03 10:30\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbCreator — static factory or annotated constructor")
    class JsonbCreatorAnnotation {

        /** POJO immuable sans no-arg ctor : @JsonbCreator sur ctor. */
        public static final class Money {
            public final String currency;
            public final long cents;
            @JsonbCreator
            public Money(@JsonbProperty("currency") String currency, @JsonbProperty("cents") long cents) {
                this.currency = currency;
                this.cents = cents;
            }
            @Override public boolean equals(Object o) {
                return o instanceof Money m && m.currency.equals(currency) && m.cents == cents;
            }
            @Override public int hashCode() { return java.util.Objects.hash(currency, cents); }
        }

        @Test
        void reads_pojo_via_jsonb_creator_constructor() {
            try (var j = JsonbBuilder.create()) {
                Money m = j.fromJson("{\"currency\":\"EUR\",\"cents\":1234}", Money.class);
                assertEquals(new Money("EUR", 1234), m);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        /** POJO avec un static factory method @JsonbCreator. */
        public static final class Tag {
            public final String value;
            private Tag(String value) { this.value = value; }
            @JsonbCreator
            public static Tag of(@JsonbProperty("value") String value) {
                return new Tag(value);
            }
            @Override public boolean equals(Object o) {
                return o instanceof Tag t && t.value.equals(value);
            }
            @Override public int hashCode() { return value.hashCode(); }
        }

        @Test
        void reads_pojo_via_jsonb_creator_static_factory() {
            try (var j = JsonbBuilder.create()) {
                Tag t = j.fromJson("{\"value\":\"prod\"}", Tag.class);
                assertEquals("prod", t.value);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbTypeAdapter — custom transformation")
    class TypeAdapter {

        public record Money(java.math.BigDecimal amount, String currency) {}

        public static final class MoneyAdapter implements JsonbAdapter<Money, String> {
            @Override public String adaptToJson(Money m) { return m.amount + " " + m.currency; }
            @Override public Money adaptFromJson(String s) {
                int sp = s.indexOf(' ');
                return new Money(new java.math.BigDecimal(s.substring(0, sp)), s.substring(sp + 1));
            }
        }

        record Order(String id, @JsonbTypeAdapter(MoneyAdapter.class) Money price) {}

        @Test
        void writes_via_adapter() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Order("o1", new Money(new java.math.BigDecimal("19.99"), "EUR")));
                assertEquals("{\"id\":\"o1\",\"price\":\"19.99 EUR\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_via_adapter() {
            try (var j = JsonbBuilder.create()) {
                Order o = j.fromJson("{\"id\":\"o1\",\"price\":\"100.50 USD\"}", Order.class);
                assertEquals("o1", o.id());
                assertEquals(new java.math.BigDecimal("100.50"), o.price().amount());
                assertEquals("USD", o.price().currency());
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void roundtrip_via_adapter() {
            try (var j = JsonbBuilder.create()) {
                Order original = new Order("o42", new Money(new java.math.BigDecimal("0.01"), "JPY"));
                String json = j.toJson(original);
                Order back = j.fromJson(json, Order.class);
                assertEquals(original, back);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Mixed @JsonbProperty + @JsonbTransient on records")
    class Mixed {

        record User(
                @JsonbProperty("user_id") String id,
                String name,
                @JsonbTransient String secret) {}

        @Test
        void rename_some_exclude_others() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new User("u1", "Alice", "hidden"));
                // id renommé, name normal, secret absent
                assertEquals("{\"user_id\":\"u1\",\"name\":\"Alice\"}", json);

                User back = j.fromJson("{\"user_id\":\"u2\",\"name\":\"Bob\",\"secret\":\"ignored\"}", User.class);
                assertEquals("u2", back.id());
                assertEquals("Bob", back.name());
                assertNull(back.secret(), "secret doit rester null après lecture, malgré la valeur dans le JSON.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
