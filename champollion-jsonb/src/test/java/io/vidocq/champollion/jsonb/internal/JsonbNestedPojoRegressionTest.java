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
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for BUG-20260611-01: properties following a nested-POJO
 * member were silently lost (root case) or surfaced as a stray
 * {@code KEY_NAME} where a collection expected the next element.
 *
 * <p>Root cause: after each member read, {@code readObjectAndApply} broke out
 * of the enclosing object whenever {@code currentEvent() == END_OBJECT} — an
 * escape hatch meant for user {@code JsonbDeserializer}s that consume up to
 * the parent's END_OBJECT, but a plain nested-POJO member also leaves its own
 * END_OBJECT as the current event. The escape hatch is now restricted to
 * user-deserializer-backed readers.</p>
 */
class JsonbNestedPojoRegressionTest {

    public static class Inner {
        public String city;
    }

    public static class Outer {
        public long id;
        public Inner inner;
        public double total;
        public boolean priority;
    }

    private static final String OUTER_JSON =
            "{\"id\":7,\"inner\":{\"city\":\"Paris\"},\"total\":87.5,\"priority\":true}";

    @Test
    @DisplayName("root POJO: properties after a nested-POJO member are populated")
    void root_pojo_keeps_properties_after_nested_pojo() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            Outer o = jsonb.fromJson(OUTER_JSON, Outer.class);
            assertEquals(7L, o.id);
            assertEquals("Paris", o.inner.city);
            assertEquals(87.5, o.total, "property after the nested POJO was dropped");
            assertTrue(o.priority, "property after the nested POJO was dropped");
        }
    }

    @Test
    @DisplayName("collection element: a non-terminal nested-POJO member must not abort the element")
    void collection_of_pojos_with_non_terminal_nested_pojo() throws Exception {
        Type listOfOuter = new ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return new Type[]{Outer.class}; }
            @Override public Type getRawType() { return List.class; }
            @Override public Type getOwnerType() { return null; }
        };
        try (var jsonb = JsonbBuilder.create()) {
            List<Outer> list = jsonb.fromJson("[" + OUTER_JSON + "," + OUTER_JSON + "]", listOfOuter);
            assertEquals(2, list.size());
            for (Outer o : list) {
                assertEquals("Paris", o.inner.city);
                assertEquals(87.5, o.total);
                assertTrue(o.priority);
            }
        }
    }

    /** Deliberately consumes everything up to and including the parent's END_OBJECT (TCK pattern). */
    public static class GreedyDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            String v = parser.getString(); // positioned on the first token of the value
            while (parser.hasNext()) {
                if (parser.next() == JsonParser.Event.END_OBJECT) break;
            }
            return v;
        }
    }

    public static class GreedyHost {
        @JsonbTypeDeserializer(GreedyDeserializer.class)
        public String w;
        public int tail;
    }

    @Test
    @DisplayName("escape hatch preserved: a user deserializer may consume up to the parent's END_OBJECT")
    void custom_deserializer_consuming_parent_end_object_still_exits_cleanly() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            GreedyHost h = jsonb.fromJson("{\"w\":\"a\",\"tail\":5}", GreedyHost.class);
            assertEquals("a", h.w);
            // 'tail' was eaten by the deserializer — the enclosing read must
            // exit cleanly instead of reading past the end of the object.
        }
    }
}
