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
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BUG-20261028-05: the members that follow a member read by a {@code JsonbDeserializer} are read, whether the
 * deserializer consumes its own object, a scalar, or (TCK pattern) runs on to the enclosing object's end.
 */
@DisplayName("BUG-20261028-05 — members after a user-deserialized member")
class UserDeserializerMemberRegressionTest {

    /** Reads {"size":n} from its START_OBJECT to its own END_OBJECT, the usual pattern. */
    public static final class SizeDeserializer implements JsonbDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            int size = 0;
            JsonParser.Event e;
            while ((e = parser.next()) != JsonParser.Event.END_OBJECT) {
                if (e == JsonParser.Event.KEY_NAME && "size".equals(parser.getString())) {
                    parser.next();
                    size = parser.getInt();
                }
            }
            return size;
        }
    }

    /** Reads the current string value with getValue, consuming nothing more. */
    public static final class UpperDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            return parser.getString().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Reads an object value in one call through getObject. */
    public static final class KeyCountDeserializer implements JsonbDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            return parser.getObject().size();
        }
    }

    /**
     * TCK pattern (AnimalListDeserializer): loops while the parser has events, so it also consumes the
     * END_OBJECT of the enclosing bean.
     */
    public static final class GreedyListDeserializer implements JsonbDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            List<String> out = new ArrayList<>();
            while (parser.hasNext()) {
                if (parser.next() == JsonParser.Event.VALUE_STRING) out.add(parser.getString());
            }
            return out;
        }
    }

    /** Reads the first number of an array value with getArray, the rest through the other reading calls. */
    public static final class ArrayFirstDeserializer implements JsonbDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            return parser.getArray().getJsonNumber(0).longValue();
        }
    }

    /** Reads an array value element by element with getValue, then a sum of their numbers. */
    public static final class SumDeserializer implements JsonbDeserializer<java.math.BigDecimal> {
        @Override
        public java.math.BigDecimal deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
            while (parser.next() != JsonParser.Event.END_ARRAY) {
                if (parser.currentEvent() != JsonParser.Event.VALUE_NUMBER) parser.getValue();
                else if (parser.isIntegralNumber()) sum = sum.add(java.math.BigDecimal.valueOf(parser.getLong()));
                else sum = sum.add(parser.getBigDecimal());
            }
            return sum;
        }
    }

    /** Skips an object value from its START_OBJECT. */
    public static final class SkippedObjectDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            parser.skipObject();
            return "skipped " + parser.currentEvent();
        }
    }

    /** Counts the elements of an array value through a stream. */
    public static final class StreamCountDeserializer implements JsonbDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            return (int) parser.getArrayStream().count();
        }
    }

    /** Skips an array value from its START_ARRAY, after checking where the parser stands. */
    public static final class SkippedArrayDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            boolean located = parser.getLocation() != null;
            parser.skipArray();
            return "skipped " + parser.currentEvent() + (located ? " located" : "");
        }
    }

    /** Reads an object value as a JsonValue. */
    public static final class ValueDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            return parser.getValue().toString();
        }
    }

    public static class Calls {
        @JsonbTypeDeserializer(ArrayFirstDeserializer.class)
        public Long first;
        @JsonbTypeDeserializer(SumDeserializer.class)
        public java.math.BigDecimal sum;
        @JsonbTypeDeserializer(SkippedObjectDeserializer.class)
        public String key;
        @JsonbTypeDeserializer(StreamCountDeserializer.class)
        public Integer count;
        @JsonbTypeDeserializer(SkippedArrayDeserializer.class)
        public String element;
        @JsonbTypeDeserializer(ValueDeserializer.class)
        public String value;
        public String after;
    }

    public static class Box {
        @JsonbTypeDeserializer(SizeDeserializer.class)
        public Integer first;
        @JsonbTypeDeserializer(UpperDeserializer.class)
        public String name;
        @JsonbTypeDeserializer(KeyCountDeserializer.class)
        public Integer keys;
        public String after;
    }

    public static class Greedy {
        public String before;
        @JsonbTypeDeserializer(GreedyListDeserializer.class)
        public List<String> tail;
    }

    @Test
    void the_members_after_an_object_read_by_a_deserializer_are_read() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Box box = jsonb.fromJson("{\"first\":{\"size\":3},\"after\":\"a\"}", Box.class);
            assertEquals(3, box.first);
            assertEquals("a", box.after);
        }
    }

    @Test
    void the_members_after_a_scalar_or_an_object_value_read_by_a_deserializer_are_read() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Box box = jsonb.fromJson("{\"keys\":{\"x\":1,\"y\":{}},\"name\":\"n\",\"after\":\"a\"}", Box.class);
            assertEquals(2, box.keys);
            assertEquals("N", box.name);
            assertEquals("a", box.after);
        }
    }

    @Test
    void a_deserializer_running_to_the_end_of_the_enclosing_object_ends_its_read() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Greedy greedy = jsonb.fromJson("{\"before\":\"b\",\"tail\":[\"x\",\"y\"]}", Greedy.class);
            assertEquals("b", greedy.before);
            assertEquals(List.of("x", "y"), greedy.tail);
        }
    }

    @Test
    void every_way_of_reading_a_value_keeps_the_enclosing_object_open() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Calls calls = jsonb.fromJson("{\"first\":[7,8],\"sum\":[1,2.5,\"x\",{}],\"key\":{\"k\":1,\"l\":{}},"
                    + "\"count\":[1,[2],3],\"element\":[\"e\",[1],{}],\"value\":{\"v\":[]},\"after\":\"a\"}",
                    Calls.class);
            assertEquals(7L, calls.first);
            assertEquals(new java.math.BigDecimal("3.5"), calls.sum);
            assertEquals("skipped END_OBJECT", calls.key);
            assertEquals(3, calls.count);
            assertEquals("skipped END_ARRAY located", calls.element);
            assertEquals("{\"v\":[]}", calls.value);
            assertEquals("a", calls.after);
        }
    }

    @Test
    void a_member_missing_after_a_deserialized_one_keeps_its_default() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Calls calls = jsonb.fromJson("{\"value\":{}}", Calls.class);
            assertEquals("{}", calls.value);
            assertNull(calls.after);
        }
    }

    @Test
    void the_members_after_a_nested_bean_with_such_a_member_are_read() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            List<Box> boxes = jsonb.fromJson("[{\"first\":{\"size\":1},\"after\":\"a\"},{\"first\":{\"size\":2}}]",
                    TypeTokens.parameterized(ArrayList.class, Box.class));
            assertEquals(2, boxes.size());
            assertEquals("a", boxes.get(0).after);
            assertEquals(2, boxes.get(1).first);
        }
    }
}
