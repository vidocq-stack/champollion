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
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Global and per-property serializers, deserializers and adapters (JSON-B 3.0 §4.7), and the serialization /
 * deserialization contexts they receive.
 */
@DisplayName("JSON-B — serializers, deserializers, adapters and their contexts")
class JsonbSerializersAndAdaptersTest {

    /** A value type hidden behind an interface, so that lookups by assignability are exercised. */
    public interface Shape { double size(); }

    public record Square(double size) implements Shape {}

    public record Point(int x, int y) {}

    public record Holder(Shape shape, Point point) {}

    public record PointHolder(Point point) {}

    /** Writes a shape through the serialization context, keyed and not keyed, with a null member. */
    public static final class ShapeSerializer implements JsonbSerializer<Shape> {
        @Override
        public void serialize(Shape shape, JsonGenerator generator, SerializationContext ctx) {
            generator.writeStartObject();
            ctx.serialize("size", shape.size(), generator);
            ctx.serialize("missing", null, generator);
            generator.writeKey("tags");
            ctx.serialize(List.of("t"), generator);
            generator.writeKey("none");
            ctx.serialize(null, generator);
            generator.writeEnd();
        }
    }

    /** Reads a shape after consuming the START_OBJECT itself, then hands each value to the context. */
    public static final class ShapeDeserializer implements JsonbDeserializer<Shape> {
        @Override
        public Shape deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            double size = 0;
            while (parser.hasNext()) {
                JsonParser.Event e = parser.next();
                if (e == JsonParser.Event.END_OBJECT) break;
                if (e != JsonParser.Event.KEY_NAME) continue;
                String key = parser.getString();
                if ("size".equals(key)) {
                    parser.next();
                    size = ctx.deserialize(Double.class, parser);
                } else {
                    parser.next();
                    ctx.deserialize((Type) Object.class, parser);
                }
            }
            return new Square(size);
        }
    }

    /** A point as "x,y". */
    public static final class PointAdapter implements JsonbAdapter<Point, String> {
        @Override public String adaptToJson(Point p) { return p.x() + "," + p.y(); }
        @Override public Point adaptFromJson(String s) {
            String[] parts = s.split(",");
            return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    /** Fails both ways. */
    public static final class FailingAdapter implements JsonbAdapter<Point, String> {
        @Override public String adaptToJson(Point p) { throw new IllegalStateException("no write"); }
        @Override public Point adaptFromJson(String s) { throw new IllegalStateException("no read"); }
    }

    /** Adapts every point to null. */
    public static final class NullAdapter implements JsonbAdapter<Point, String> {
        @Override public String adaptToJson(Point p) { return null; }
        @Override public Point adaptFromJson(String s) { return null; }
    }

    /** A bean whose properties carry their own serializer, deserializer and adapter. */
    public static class Annotated {
        @JsonbTypeAdapter(PointAdapter.class)
        private Point corner;
        private Shape shape;
        @JsonbTypeSerializer(ShapeSerializer.class)
        @JsonbTypeDeserializer(ShapeDeserializer.class)
        public Shape publicShape;

        public Annotated() {
            // JSON-B instantiates the bean through its public no-argument constructor
        }

        public Point getCorner() { return corner; }
        public void setCorner(Point corner) { this.corner = corner; }

        @JsonbTypeSerializer(ShapeSerializer.class)
        public Shape getShape() { return shape; }
        @JsonbTypeDeserializer(ShapeDeserializer.class)
        public void setShape(Shape shape) { this.shape = shape; }
    }

    private static JsonbConfig serializers() {
        return new JsonbConfig().withSerializers(new ShapeSerializer()).withDeserializers(new ShapeDeserializer());
    }

    @Test
    void global_serializer_and_deserializer_apply_to_implementations() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(serializers())) {
            String json = jsonb.toJson(new Holder(new Square(2), new Point(1, 2)));
            assertEquals("{\"shape\":{\"size\":2.0,\"missing\":null,\"tags\":[\"t\"],\"none\":null},"
                    + "\"point\":{\"x\":1,\"y\":2}}", json);
            var out = jsonb.fromJson(json, Holder.class);
            assertEquals(new Holder(new Square(2), new Point(1, 2)), out);
            assertEquals("{\"size\":3.0,\"missing\":null,\"tags\":[\"t\"],\"none\":null}",
                    jsonb.toJson(new Square(3)));
            assertEquals(new Square(4), jsonb.fromJson("{\"size\":4,\"extra\":[1,{\"a\":true}]}", Shape.class));
        }
    }

    @Test
    void a_null_member_skips_the_global_serializer() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(serializers().withNullValues(true))) {
            assertEquals("{\"shape\":null,\"point\":null}", jsonb.toJson(new Holder(null, null)));
        }
    }

    @Test
    void global_adapter_applies_to_members_and_roots() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withAdapters(new PointAdapter()))) {
            String json = jsonb.toJson(new PointHolder(new Point(3, 4)));
            assertEquals("{\"point\":\"3,4\"}", json);
            assertEquals(new PointHolder(new Point(3, 4)), jsonb.fromJson(json, PointHolder.class));
            assertNull(jsonb.fromJson("{\"point\":null}", PointHolder.class).point());
            assertEquals(Map.of("p", new Point(5, 6)), jsonb.fromJson("{\"p\":\"5,6\"}",
                    TypeTokens.parameterized(java.util.HashMap.class, String.class, Point.class)));
        }
    }

    @Test
    void a_global_adapter_returning_null_writes_null() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withAdapters(new NullAdapter()))) {
            assertEquals("[null]", jsonb.toJson(List.of(new Point(1, 1)),
                    TypeTokens.parameterized(java.util.ArrayList.class, Point.class)));
        }
    }

    @Test
    void a_failing_global_adapter_is_a_jsonb_exception() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withAdapters(new FailingAdapter()))) {
            var holder = new PointHolder(new Point(1, 1));
            assertThrows(JsonbException.class, () -> jsonb.toJson(holder));
            assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"point\":\"1,1\"}", PointHolder.class));
        }
    }

    @Test
    void per_property_serializer_deserializer_and_adapter() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            var in = new Annotated();
            in.setCorner(new Point(7, 8));
            in.setShape(new Square(1));
            in.publicShape = new Square(5);
            String json = jsonb.toJson(in);
            assertEquals("{\"corner\":\"7,8\",\"publicShape\":{\"size\":5.0,\"missing\":null,\"tags\":[\"t\"],"
                    + "\"none\":null},\"shape\":{\"size\":1.0,\"missing\":null,\"tags\":[\"t\"],\"none\":null}}", json);
            // A property read by a user deserializer must come last here: BUG-20261028-05 (still open) drops the
            // properties that follow it.
            var viaSetter = jsonb.fromJson("{\"corner\":\"7,8\",\"shape\":{\"size\":1}}", Annotated.class);
            assertEquals(new Point(7, 8), viaSetter.getCorner());
            assertEquals(new Square(1), viaSetter.getShape());
            var viaField = jsonb.fromJson("{\"publicShape\":{\"size\":5}}", Annotated.class);
            assertEquals(new Square(5), viaField.publicShape);
        }
    }

    @Test
    void per_property_serializer_writes_null_members_when_nillable() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withNullValues(true))) {
            assertEquals("{\"corner\":null,\"publicShape\":null,\"shape\":null}", jsonb.toJson(new Annotated()));
        }
    }
}
