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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Champollion JSON-B 3.0 — containers (M4.3 write)")
class ChampollionJsonbContainersTest {

    @Nested
    @DisplayName("Lists")
    class Lists {

        @Test
        void empty_list() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("[]", j.toJson(List.of()));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void list_of_primitives() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("[1,2,3]", j.toJson(List.of(1, 2, 3)));
                assertEquals("[\"a\",\"b\"]", j.toJson(List.of("a", "b")));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void list_of_records() {
            try (var j = JsonbBuilder.create()) {
                record P(int x, int y) {}
                String json = j.toJson(List.of(new P(1, 2), new P(3, 4)));
                assertEquals("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void nested_lists() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("[[1,2],[3,4]]", j.toJson(List.of(List.of(1, 2), List.of(3, 4))));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Sets")
    class Sets {

        @Test
        void set_serialized_as_array() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(Set.of(1));
                assertEquals("[1]", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Maps — only String keys per spec §3.6")
    class Maps {

        @Test
        void empty_map() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("{}", j.toJson(Map.of()));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void map_with_primitive_values() {
            try (var j = JsonbBuilder.create()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("a", 1);
                m.put("b", "x");
                m.put("c", true);
                assertEquals("{\"a\":1,\"b\":\"x\",\"c\":true}", j.toJson(m));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void map_with_record_values() {
            try (var j = JsonbBuilder.create()) {
                record P(int x) {}
                var m = new LinkedHashMap<String, Object>();
                m.put("a", new P(1));
                m.put("b", new P(2));
                assertEquals("{\"a\":{\"x\":1},\"b\":{\"x\":2}}", j.toJson(m));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Arrays")
    class Arrays {

        @Test
        void int_array() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("[1,2,3]", j.toJson(new int[] {1, 2, 3}));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void string_array() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("[\"a\",\"b\"]", j.toJson(new String[] {"a", "b"}));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void object_array_with_records() {
            try (var j = JsonbBuilder.create()) {
                record P(int x) {}
                assertEquals("[{\"x\":1},{\"x\":2}]", j.toJson(new P[] {new P(1), new P(2)}));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Optional")
    class Optionals {

        @Test
        void optional_present_serializes_inner_value() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("\"hello\"", j.toJson(Optional.of("hello")));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void optional_empty_serializes_as_null() {
            try (var j = JsonbBuilder.create()) {
                assertEquals("null", j.toJson(Optional.empty()));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void record_with_optional_field_is_omitted_when_empty() {
            try (var j = JsonbBuilder.create()) {
                record User(String name, Optional<String> email) {}
                String json = j.toJson(new User("Alice", Optional.empty()));
                assertEquals("{\"name\":\"Alice\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Records with composite fields")
    class Composite {

        @Test
        void record_with_list_field() {
            try (var j = JsonbBuilder.create()) {
                record Book(String title, List<String> tags) {}
                String json = j.toJson(new Book("J", List.of("x", "y")));
                assertEquals("{\"title\":\"J\",\"tags\":[\"x\",\"y\"]}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void record_with_map_field() {
            try (var j = JsonbBuilder.create()) {
                record Cfg(String name, Map<String, Integer> settings) {}
                var settings = new LinkedHashMap<String, Integer>();
                settings.put("k", 1);
                String json = j.toJson(new Cfg("c", settings));
                assertEquals("{\"name\":\"c\",\"settings\":{\"k\":1}}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
