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
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@link Jsonb} may be called again while it reads: an adapter that reads its own JSON with the same instance
 * must not disturb the read that called it (BUG-20261029-07).
 */
@DisplayName("JSON-B — a Jsonb called again while it reads")
class ChampollionJsonbReentrancyTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public static class Point {
        public int x;
        public int y;
    }

    /** Reads the point a string holds with the same {@link Jsonb} that reads the enclosing object. */
    public static class PointAdapter implements JsonbAdapter<Point, String> {
        @Override
        public String adaptToJson(Point point) {
            return jsonb.toJson(point);
        }

        @Override
        public Point adaptFromJson(String json) {
            return jsonb.fromJson(json, Point.class);
        }
    }

    public static class Shape {
        public String name;
        @JsonbTypeAdapter(PointAdapter.class)
        public Point origin;
        public String colour;
    }

    private static final String SHAPE =
            "{\"name\":\"square\",\"origin\":\"{\\\"x\\\":3,\\\"y\\\":4}\",\"colour\":\"red\"}";

    private static void assertShape(Shape shape) {
        assertEquals("square", shape.name);
        assertEquals(3, shape.origin.x);
        assertEquals(4, shape.origin.y);
        assertEquals("red", shape.colour, "the enclosing read goes on after the adapter's");
    }

    @Test
    void an_adapter_reading_with_the_same_jsonb_leaves_the_enclosing_string_read_intact() {
        assertShape(jsonb.fromJson(SHAPE, Shape.class));
    }

    @Test
    void an_adapter_reading_with_the_same_jsonb_leaves_the_enclosing_reader_read_intact() {
        assertShape(jsonb.fromJson(new StringReader(SHAPE), Shape.class));
    }

    @Test
    void reads_on_many_virtual_threads_do_not_share_a_parser() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reads = IntStream.range(0, 200)
                    .mapToObj(i -> executor.submit(() -> jsonb.fromJson(
                            "{\"x\":" + i + ",\"y\":" + -i + "}", Point.class)))
                    .toList();
            for (int i = 0; i < reads.size(); i++) {
                Future<Point> read = reads.get(i);
                Point point = read.get();
                assertEquals(i, point.x);
                assertEquals(-i, point.y);
            }
        }
    }
}
