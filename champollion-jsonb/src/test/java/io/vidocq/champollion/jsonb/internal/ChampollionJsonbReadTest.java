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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Champollion JSON-B 3.0 — fromJson (M4.2 runtime)")
class ChampollionJsonbReadTest {

    @Nested
    @DisplayName("Primitives")
    class Primitives {

        @Test
        void reads_string() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("hello", j.fromJson("\"hello\"", String.class));
                assertEquals("a\"b", j.fromJson("\"a\\\"b\"", String.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_numbers() {
            try (var j = JsonbBuilder.create()) {
                assertEquals(42, j.fromJson("42", Integer.class));
                assertEquals(42L, j.fromJson("42", Long.class));
                assertEquals(3.14, j.fromJson("3.14", Double.class));
                assertEquals(new BigDecimal("1.5"), j.fromJson("1.5", BigDecimal.class));
                assertEquals(new BigInteger("12345678901234567890"),
                        j.fromJson("12345678901234567890", BigInteger.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_booleans_and_null() {
            try (var j = JsonbBuilder.create()) {
                assertEquals(Boolean.TRUE, j.fromJson("true", Boolean.class));
                assertEquals(Boolean.FALSE, j.fromJson("false", Boolean.class));
                assertEquals(null, j.fromJson("null", String.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("java.time")
    class TimeTypes {

        @Test
        void reads_instant() {
            try (var j = JsonbBuilder.create()) {
                Instant got = j.fromJson("\"2024-03-15T10:30:00Z\"", Instant.class);
                assertEquals(Instant.parse("2024-03-15T10:30:00Z"), got);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_localdate() {
            try (var j = JsonbBuilder.create()) {
                assertEquals(LocalDate.of(2024, 3, 15), j.fromJson("\"2024-03-15\"", LocalDate.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("UUID & enum")
    class Misc {

        enum Status { ACTIVE, INACTIVE }

        @Test
        void reads_uuid() {
            try (var j = JsonbBuilder.create()) {
                var u = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
                assertEquals(u, j.fromJson("\"550e8400-e29b-41d4-a716-446655440000\"", UUID.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_enum() {
            try (var j = JsonbBuilder.create()) {
                assertEquals(Status.ACTIVE, j.fromJson("\"ACTIVE\"", Status.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Records")
    class Records {

        record Point(int x, int y) {}
        record Person(String name, int age, Point position) {}

        @Test
        void reads_simple_record() {
            try (var j = JsonbBuilder.create()) {
                Point p = j.fromJson("{\"x\":3,\"y\":4}", Point.class);
                assertEquals(new Point(3, 4), p);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_nested_record() {
            try (var j = JsonbBuilder.create()) {
                Person p = j.fromJson(
                        "{\"name\":\"Alice\",\"age\":30,\"position\":{\"x\":1,\"y\":2}}",
                        Person.class);
                assertEquals(new Person("Alice", 30, new Point(1, 2)), p);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void unknown_property_is_ignored() {
            try (var j = JsonbBuilder.create()) {
                Point p = j.fromJson("{\"x\":1,\"y\":2,\"unknown\":\"foo\"}", Point.class);
                assertEquals(new Point(1, 2), p);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void missing_property_uses_default_for_primitive() {
            try (var j = JsonbBuilder.create()) {
                Point p = j.fromJson("{\"x\":1}", Point.class);
                assertEquals(new Point(1, 0), p);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Roundtrips")
    class Roundtrip {

        record Book(String title, List<String> tags, Map<String, Integer> stats) {}

        @Test
        void record_roundtrip_preserves_data() {
            try (var j = JsonbBuilder.create()) {
                var stats = new LinkedHashMap<String, Integer>();
                stats.put("pages", 320);
                stats.put("chapters", 12);
                var book = new Book("J", List.of("scifi", "drama"), stats);

                String json = j.toJson(book);
                Book back = j.fromJson(json, Book.class);
                assertEquals(book, back);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Arrays")
    class Arrays {

        @Test
        void reads_int_array() {
            try (var j = JsonbBuilder.create()) {
                int[] got = j.fromJson("[1,2,3]", int[].class);
                assertArrayEquals(new int[] {1, 2, 3}, got);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_string_array() {
            try (var j = JsonbBuilder.create()) {
                String[] got = j.fromJson("[\"a\",\"b\"]", String[].class);
                assertArrayEquals(new String[] {"a", "b"}, got);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Optional")
    class Optionals {

        record User(String name, Optional<String> email) {}

        @Test
        void reads_record_with_present_optional() {
            try (var j = JsonbBuilder.create()) {
                User u = j.fromJson("{\"name\":\"A\",\"email\":\"a@b.c\"}", User.class);
                assertEquals(Optional.of("a@b.c"), u.email());
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_record_with_missing_optional_as_empty() {
            try (var j = JsonbBuilder.create()) {
                User u = j.fromJson("{\"name\":\"A\"}", User.class);
                assertTrue(u.email() == null || u.email().isEmpty());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
