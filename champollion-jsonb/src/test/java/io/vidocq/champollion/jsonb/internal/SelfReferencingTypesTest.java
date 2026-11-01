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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A type that holds itself, through a container, is bound like any other (BUG-20261101-01). */
class SelfReferencingTypesTest {

    public record Entry(String name, Optional<Entry> next) {}

    public record Node(String name, List<Node> children) {}

    public record Tree(String label, Map<String, Tree> branches) {}

    public static class Person {
        public String name;
        public List<Person> friends;

        @Override public boolean equals(Object o) {
            return o instanceof Person p && java.util.Objects.equals(name, p.name)
                    && java.util.Objects.equals(friends, p.friends);
        }

        @Override public int hashCode() { return java.util.Objects.hash(name); }
    }

    @Test
    void aRecordHoldingAnOptionalOfItself() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Entry e = new Entry("a", Optional.of(new Entry("b", Optional.empty())));
            String json = jsonb.toJson(e);
            assertEquals("{\"name\":\"a\",\"next\":{\"name\":\"b\"}}", json);
            assertEquals(e, jsonb.fromJson(json, Entry.class));
        }
    }

    @Test
    void aRecordHoldingAListAndAMapOfItself() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Node n = new Node("root", List.of(new Node("leaf", List.of())));
            String json = jsonb.toJson(n);
            assertEquals("{\"name\":\"root\",\"children\":[{\"name\":\"leaf\",\"children\":[]}]}", json);
            assertEquals(n, jsonb.fromJson(json, Node.class));
            Tree t = new Tree("t", Map.of("x", new Tree("y", Map.of())));
            assertEquals(t, jsonb.fromJson(jsonb.toJson(t), Tree.class));
        }
    }

    @Test
    void aClassHoldingAListOfItself() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Person a = new Person();
            a.name = "a";
            Person b = new Person();
            b.name = "b";
            b.friends = List.of();
            a.friends = List.of(b);
            assertEquals(a, jsonb.fromJson(jsonb.toJson(a), Person.class));
        }
    }
}
