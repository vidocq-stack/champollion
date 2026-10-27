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
