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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Jsonb.fromJson(InputStream, Type)} is how a REST runtime reads a request entity. A creation body of
 * 50 bytes used to cost 2 848 bytes (BENCH-20261029-01): a pushback stream, a UTF-8 decoder and its 1 KB byte
 * buffer, a reader, and a parser with its 512-character buffer, all new on every call. A body that fits the
 * buffer the {@link Jsonb} keeps is now read whole, decoded to a string and parsed with the kept parser.
 */
class FromJsonOnStreamTest {

    public record TodoInput(String title, boolean done) {}

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    private static TodoInput read(byte[] body) {
        return jsonb.fromJson(new ByteArrayInputStream(body), TodoInput.class);
    }

    @Test
    void aSmallBodyIsReadWithoutDecoderNorReaderBuffers() {
        byte[] body = "{\"title\":\"Write the benchmark report\",\"done\":false}".getBytes(StandardCharsets.UTF_8);
        assertEquals(new TodoInput("Write the benchmark report", false), read(body));
        long perCall = bytesPerCall(() -> read(body));
        // The body as a string, the title, the record and the test's own stream: no parser, tokenizer nor key.
        assertTrue(perCall < 320, "fromJson of a 50-byte body allocates " + perCall + " bytes per call");
    }

    @Test
    void nonAsciiTextIsDecodedAsUtf8() {
        assertEquals(new TodoInput("café ☕ 😀 \"quoted\"", true),
                read("{\"title\":\"café ☕ 😀 \\\"quoted\\\"\",\"done\":true}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aUtf8ByteOrderMarkIsSkipped() {
        byte[] json = "{\"title\":\"bom\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[json.length + 3];
        body[0] = (byte) 0xEF;
        body[1] = (byte) 0xBB;
        body[2] = (byte) 0xBF;
        System.arraycopy(json, 0, body, 3, json.length);
        assertEquals(new TodoInput("bom", true), read(body));
    }

    @Test
    void utf16AndUtf32BodiesAreStillDetected() {
        String json = "{\"title\":\"tâche 😀\",\"done\":true}";
        for (String charset : new String[] {"UTF-16BE", "UTF-16LE", "UTF-16", "UTF-32BE", "UTF-32LE"}) {
            assertEquals(new TodoInput("tâche 😀", true), read(json.getBytes(Charset.forName(charset))), charset);
        }
    }

    @Test
    void malformedUtf8IsReplacedAsTheStreamingDecoderReplacesIt() {
        byte[] body = {'{', '"', 't', 'i', 't', 'l', 'e', '"', ':', '"', 'a', (byte) 0xC3, '"', '}'};
        assertEquals(new TodoInput("a�", false), read(body));
    }

    @Test
    void bodiesAroundTheBufferSizeAreReadWhole() {
        for (int length : new int[] {8_150, 8_191, 8_192, 8_193, 8_194, 20_000}) {
            String title = "x".repeat(length - "{\"title\":\"\",\"done\":true}".length());
            byte[] body = ("{\"title\":\"" + title + "\",\"done\":true}").getBytes(StandardCharsets.UTF_8);
            assertEquals(length, body.length);
            assertEquals(new TodoInput(title, true), read(body), "body of " + length + " bytes");
        }
    }

    @Test
    void aBodyDeliveredInSmallChunksIsReadWhole() {
        byte[] body = "{\"title\":\"one byte at a time\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
        InputStream trickle = new ByteArrayInputStream(body) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        assertEquals(new TodoInput("one byte at a time", true), jsonb.fromJson(trickle, TodoInput.class));
    }

    /** Counts its closes. */
    private static class ClosingStream extends ByteArrayInputStream {
        int closes;

        ClosingStream(byte[] body) {
            super(body);
        }

        @Override public void close() {
            closes++;
        }
    }

    @Test
    void theStreamIsClosedWhateverPathReadsIt() {
        String json = "{\"title\":\"t\",\"done\":false}";
        byte[][] bodies = {
                json.getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_16LE),
                ("{\"title\":\"" + "y".repeat(10_000) + "\",\"done\":false}").getBytes(StandardCharsets.UTF_8)};
        for (byte[] body : bodies) {
            var stream = new ClosingStream(body);
            jsonb.fromJson(stream, TodoInput.class);
            assertTrue(stream.closes > 0, "a body of " + body.length + " bytes left its stream open");
        }
    }

    @Test
    void aStreamThatFailsIsReportedAndClosed() {
        var ioFailing = new ClosingStream(new byte[0]) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return sneaky(new java.io.IOException("connection reset"));
            }
        };
        var thrown = assertThrows(jakarta.json.bind.JsonbException.class, () -> jsonb.fromJson(ioFailing, TodoInput.class));
        assertEquals("connection reset", thrown.getCause().getMessage());
        assertTrue(ioFailing.closes > 0, "a failing stream was left open");
    }

    /** Throws a checked exception from a method that does not declare it, as a real stream's read may. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> int sneaky(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    void emptyAndInvalidBodiesStillFail() {
        byte[] empty = new byte[0];
        byte[] truncated = "{\"title\":".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> read(empty));
        assertThrows(RuntimeException.class, () -> read(truncated));
    }

    /** Reads its value, a JSON text inside a string, with the same {@link Jsonb}, from a stream. */
    public static class NestedTodo implements JsonbAdapter<TodoInput, String> {
        @Override public String adaptToJson(TodoInput todo) {
            return jsonb.toJson(todo);
        }

        @Override public TodoInput adaptFromJson(String json) {
            return jsonb.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), TodoInput.class);
        }
    }

    public record Envelope(String name, @JsonbTypeAdapter(NestedTodo.class) TodoInput todo, int count) {}

    @Test
    void anAdapterMayReadAStreamWithTheSameJsonbWhileItReads() {
        byte[] body = "{\"name\":\"n\",\"todo\":\"{\\\"title\\\":\\\"inner\\\",\\\"done\\\":true}\",\"count\":3}"
                .getBytes(StandardCharsets.UTF_8);
        Envelope envelope = jsonb.fromJson(new ByteArrayInputStream(body), Envelope.class);
        assertEquals(new Envelope("n", new TodoInput("inner", true), 3), envelope);
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
