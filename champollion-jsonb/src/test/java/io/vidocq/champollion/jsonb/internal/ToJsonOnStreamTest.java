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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Jsonb.toJson(Object, OutputStream)} is how a REST runtime writes a response entity
 * (JSON-B 3.0 §3.1, UTF-8 by default per RFC 8259 §8.1). It used to cost an
 * {@code OutputStreamWriter} and a generator buffer of 1024 characters per call; the generator
 * now encodes UTF-8 itself into a buffer that starts small.
 */
class ToJsonOnStreamTest {

    public record Todo(long id, String title, boolean completed) {}

    @Test
    void aSmallObjectIsWrittenToAStreamWithoutAKilobyteOfBuffers() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            var todo = new Todo(42, "buy milk", false);
            var out = new ByteArrayOutputStream(256);
            Runnable call = () -> {
                out.reset();
                jsonb.toJson(todo, out);
            };
            call.run();
            assertEquals("{\"id\":42,\"title\":\"buy milk\",\"completed\":false}", out.toString(StandardCharsets.UTF_8));
            long perCall = bytesPerCall(call);
            assertTrue(perCall < 1_024, "toJson of a small record on a stream allocates " + perCall + " bytes per call");
        }
    }

    @Test
    void nonAsciiTextIsWrittenInUtf8() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            var out = new ByteArrayOutputStream();
            jsonb.toJson(new Todo(1, "café ☕ 😀 \"quoted\"", true), out);
            assertEquals("{\"id\":1,\"title\":\"café ☕ 😀 \\\"quoted\\\"\",\"completed\":true}",
                    out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aLargeListOnAStreamMatchesTheStringForm() throws Exception {
        List<Todo> todos = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) todos.add(new Todo(i, "tâche n°" + i + " 😀", i % 2 == 0));
        try (Jsonb jsonb = JsonbBuilder.create(); Jsonb pretty = JsonbBuilder.create(new JsonbConfig().withFormatting(true))) {
            for (Jsonb j : List.of(jsonb, pretty)) {
                var out = new ByteArrayOutputStream();
                j.toJson(todos, out);
                assertEquals(j.toJson(todos), out.toString(StandardCharsets.UTF_8));
            }
        }
    }

    /** A list of {@code element}, as a REST layer builds it from the generic return type of a resource method. */
    private record ListOf(java.lang.reflect.Type element) implements java.lang.reflect.ParameterizedType {
        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return new java.lang.reflect.Type[] {element}; }
        @Override public java.lang.reflect.Type getRawType() { return List.class; }
        @Override public java.lang.reflect.Type getOwnerType() { return null; }
    }

    private static List<Todo> todos(int n) {
        List<Todo> todos = new ArrayList<>();
        for (int i = 1; i <= n; i++) todos.add(new Todo(i, "Todo " + i, false));
        return List.copyOf(todos);
    }

    @Test
    void aListOfAHundredTodosOnAStreamAllocatesNoBufferPerCall() throws Exception {
        // BENCH-20261029-01: 21 640 bytes per call, of which ~8 KB of generator buffer grown from 128 bytes
        // to 4 KB, and one string per id.
        try (Jsonb jsonb = JsonbBuilder.create()) {
            List<Todo> todos = todos(100);
            var type = new ListOf(Todo.class);
            var out = new ByteArrayOutputStream(8_192);
            Runnable call = () -> {
                out.reset();
                jsonb.toJson(todos, type, out);
            };
            call.run();
            assertEquals(jsonb.toJson(todos, type), out.toString(StandardCharsets.UTF_8));
            long perCall = bytesPerCall(call);
            assertTrue(perCall < 1_024, "toJson of 100 todos on a stream allocates " + perCall + " bytes per call");
        }
    }

    @Test
    void successiveWritesOfDifferentSizesEachGetTheirOwnBytes() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            for (int n : new int[] {1_000, 1, 0, 300, 2}) {
                List<Todo> todos = todos(n);
                var out = new ByteArrayOutputStream();
                jsonb.toJson(todos, out);
                assertEquals(jsonb.toJson(todos), out.toString(StandardCharsets.UTF_8));
            }
        }
    }

    /** Writes each record, and first writes its id and title with the same {@link Jsonb}, to a stream of its own. */
    public static class EchoingSerializer implements jakarta.json.bind.serializer.JsonbSerializer<Todo> {
        static Jsonb jsonb;
        static final ByteArrayOutputStream echoes = new ByteArrayOutputStream();

        @Override
        public void serialize(Todo todo, jakarta.json.stream.JsonGenerator generator,
                              jakarta.json.bind.serializer.SerializationContext ctx) {
            jsonb.toJson(List.of(todo.id(), todo.title()), echoes);
            generator.writeStartObject().write("id", todo.id()).write("title", todo.title()).writeEnd();
        }
    }

    @Test
    void aSerializerMayWriteToAnotherStreamWithTheSameJsonb() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withSerializers(new EchoingSerializer()))) {
            EchoingSerializer.jsonb = jsonb;
            EchoingSerializer.echoes.reset();
            var out = new ByteArrayOutputStream();
            jsonb.toJson(todos(2), new ListOf(Todo.class), out);
            assertEquals("[{\"id\":1,\"title\":\"Todo 1\"},{\"id\":2,\"title\":\"Todo 2\"}]",
                    out.toString(StandardCharsets.UTF_8));
            assertEquals("[1,\"Todo 1\"][2,\"Todo 2\"]", EchoingSerializer.echoes.toString(StandardCharsets.UTF_8));
        }
    }

    /** Mean bytes the current thread allocates per call, after a warm-up. HotSpot accounts them
     *  exactly; {@code com.sun.management} is reached through reflection because the module
     *  under test reads neither {@code java.management} nor {@code jdk.management}. */
    private static long bytesPerCall(Runnable call) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            for (int i = 0; i < 2_000; i++) call.run();
            long before = (Long) allocated.invoke(threads);
            for (int i = 0; i < 5_000; i++) call.run();
            return ((Long) allocated.invoke(threads) - before) / 5_000;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }
}
