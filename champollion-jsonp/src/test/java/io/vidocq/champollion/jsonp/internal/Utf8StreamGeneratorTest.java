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
package io.vidocq.champollion.jsonp.internal;

import io.vidocq.champollion.spi.RawJsonKeyWriter;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A generator created on an {@link OutputStream} without a charset writes UTF-8 (JSON-P 2.1
 * {@code JsonProvider#createGenerator(OutputStream)}, RFC 8259 §8.1). It used to reach the stream
 * through an {@code OutputStreamWriter}, whose encoder keeps buffers of its own, behind a buffer
 * of 1024 characters allocated by every generator: a few kilobytes per REST response of a few
 * dozen bytes. The generator now encodes straight into a byte buffer that starts small.
 *
 * <p>The bytes must stay those the {@code OutputStreamWriter} produced, which are those of
 * {@code String.getBytes(UTF_8)} of the text a {@link java.io.Writer} target receives: a lone
 * surrogate becomes {@code '?'}.</p>
 */
class Utf8StreamGeneratorTest {

    private static final ChampollionJsonProvider PROVIDER = new ChampollionJsonProvider();

    /** The bytes a generator on a stream produces for {@code body}. */
    private static byte[] toStream(Consumer<JsonGenerator> body) {
        var out = new ByteArrayOutputStream();
        try (JsonGenerator g = PROVIDER.createGenerator(out)) {
            body.accept(g);
        }
        return out.toByteArray();
    }

    /** The bytes the same {@code body} produces on a Writer, encoded as the stream must encode them. */
    private static byte[] reference(Consumer<JsonGenerator> body) {
        var out = new StringWriter();
        try (JsonGenerator g = PROVIDER.createGenerator(out)) {
            body.accept(g);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void assertSameAsWriter(Consumer<JsonGenerator> body) {
        assertArrayEquals(reference(body), toStream(body));
    }

    private static void smallTodo(JsonGenerator g) {
        g.writeStartObject();
        g.write("id", 42);
        g.write("title", "buy milk");
        g.write("completed", false);
        g.writeEnd();
    }

    @Test
    void aSmallDocumentOnAStreamAllocatesNeitherAWriterNorAKilobyteBuffer() {
        var out = new ByteArrayOutputStream(256);
        Runnable call = () -> {
            out.reset();
            try (JsonGenerator g = PROVIDER.createGenerator(out)) {
                smallTodo(g);
            }
        };
        call.run();
        assertEquals("{\"id\":42,\"title\":\"buy milk\",\"completed\":false}", out.toString(StandardCharsets.UTF_8));
        long perCall = bytesPerCall(call);
        assertTrue(perCall < 768, "generating a 47-byte document on a stream allocates " + perCall + " bytes per call");
    }

    @Test
    void multiByteCharactersAreEncodedInUtf8() {
        byte[] bytes = toStream(g -> g.writeStartArray().write("é").write("€").write("中文").write("😀").writeEnd());
        assertEquals("[\"é\",\"€\",\"中文\",\"😀\"]", new String(bytes, StandardCharsets.UTF_8));
        assertEquals(1 + 4 + 1 + 5 + 1 + 8 + 1 + 6 + 1, bytes.length);
    }

    @Test
    void namesAndRawKeysAreEncodedInUtf8() {
        assertSameAsWriter(g -> {
            g.writeStartObject();
            g.write("clé", "valeur");
            g.write("名前", "😀");
            ((RawJsonKeyWriter) g).writeKeyRaw("\"ключ\"");
            g.write(1);
            ((RawJsonKeyWriter) g).writeKeyRawWithColon("\"emoji😀\":");
            g.write(true);
            g.writeEnd();
        });
    }

    @Test
    void escapesAreUnchanged() {
        assertSameAsWriter(g -> g.write("q\"b\\s/\b\f\n\r\t\u0001\u001f é\u007f\u0080߿ࠀ￿"));
    }

    @Test
    void loneSurrogatesAreReplacedAsTheEncoderDid() {
        assertSameAsWriter(g -> g.writeStartArray()
                .write("a\uD83Db")          // high surrogate followed by a letter
                .write("a\uDE00b")          // low surrogate alone
                .write("\uD83D")            // high surrogate at the end of the string
                .write("\uDE00\uD83D")      // low then high: both lone
                .write("\uD83D😀") // lone high then a pair
                .write("\uD83D\n\uDE00")    // a pair broken by an escape
                .writeEnd());
        byte[] bytes = toStream(g -> g.write("a\uD83Db"));
        assertEquals("\"a?b\"", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void aLargeDocumentCrossesTheBufferBoundariesAtEveryOffset() {
        Consumer<JsonGenerator> body = g -> {
            g.writeStartArray();
            for (int i = 0; i < 3_000; i++) {
                g.writeStartObject();
                g.write("i", i);
                g.write("s", "x".repeat(i % 7) + "é€😀中" + "\\\"".repeat(i % 3));
                g.write("d", i / 3.0);
                g.writeEnd();
            }
            g.write("y".repeat(20_000) + "😀");
            g.write("€".repeat(9_000));
            g.writeEnd();
        };
        assertSameAsWriter(body);
        assertTrue(toStream(body).length > 100_000);
    }

    @Test
    void prettyPrintingOnAStreamIsUnchanged() {
        var factory = PROVIDER.createGeneratorFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        var bytes = new ByteArrayOutputStream();
        try (JsonGenerator g = factory.createGenerator(bytes)) {
            g.writeStartObject().write("ä", 1).writeStartArray("l").write("😀").writeEnd().writeEnd();
        }
        var chars = new StringWriter();
        try (JsonGenerator g = factory.createGenerator(chars)) {
            g.writeStartObject().write("ä", 1).writeStartArray("l").write("😀").writeEnd().writeEnd();
        }
        assertEquals(chars.toString(), bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anExplicitCharsetStillApplies() {
        var factory = PROVIDER.createGeneratorFactory(Map.of());
        var utf8 = new ByteArrayOutputStream();
        try (JsonGenerator g = factory.createGenerator(utf8, StandardCharsets.UTF_8)) {
            g.write("é😀");
        }
        assertEquals("\"é😀\"", utf8.toString(StandardCharsets.UTF_8));
        var utf16 = new ByteArrayOutputStream();
        try (JsonGenerator g = factory.createGenerator(utf16, StandardCharsets.UTF_16BE)) {
            g.write("é😀");
        }
        assertEquals("\"é😀\"", utf16.toString(StandardCharsets.UTF_16BE));
    }

    @Test
    void theObjectModelWriterOnAStreamIsUnchanged() {
        var value = PROVIDER.createObjectBuilder().add("nom", "Zoé 😀").add("n", 1).build();
        var out = new ByteArrayOutputStream();
        try (var w = PROVIDER.createWriter(out)) {
            w.writeObject(value);
        }
        assertEquals("{\"nom\":\"Zoé 😀\",\"n\":1}", out.toString(StandardCharsets.UTF_8));
    }

    /** Records what reaches it, and whether it was flushed or closed. */
    private static final class RecordingStream extends OutputStream {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        int flushes;
        boolean closed;

        @Override public void write(int b) { content.write(b); }
        @Override public void write(byte[] b, int off, int len) { content.write(b, off, len); }
        @Override public void flush() { flushes++; }
        @Override public void close() { closed = true; }
    }

    @Test
    void flushHandsTheStreamWhatWasWrittenAndFlushesIt() {
        var stream = new RecordingStream();
        JsonGenerator g = PROVIDER.createGenerator(stream);
        g.writeStartArray().write("é");
        assertEquals(0, stream.content.size(), "a small document stays in the generator until flushed");
        g.flush();
        assertEquals("[\"é\"", stream.content.toString(StandardCharsets.UTF_8));
        assertEquals(1, stream.flushes);
        assertFalse(stream.closed);
        g.writeEnd();
        g.close();
        assertEquals("[\"é\"]", stream.content.toString(StandardCharsets.UTF_8));
        assertTrue(stream.closed, "close() closes the stream (JSON-P 2.1 JsonGenerator#close)");
    }

    @Test
    void closingAnIncompleteDocumentFailsWithoutClosingTheStream() {
        var stream = new RecordingStream();
        JsonGenerator g = PROVIDER.createGenerator(stream);
        g.writeStartObject();
        assertThrows(JsonGenerationException.class, g::close);
        assertFalse(stream.closed);
    }

    @Test
    void aFailingStreamSurfacesAsAJsonException() {
        var failing = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("disk full"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("disk full"); }
        };
        JsonGenerator g = PROVIDER.createGenerator(failing);
        g.write(1);
        var e = assertThrows(JsonException.class, g::flush);
        assertEquals("disk full", e.getCause().getMessage());
    }

    @Test
    void failingFlushAndCloseSurfaceAsJsonExceptions() {
        var flushFails = new OutputStream() {
            @Override public void write(int b) {
                // Accepted and dropped.
            }
            @Override public void flush() throws IOException { throw new IOException("flush failed"); }
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        JsonGenerator g = PROVIDER.createGenerator(flushFails);
        g.write(1);
        assertEquals("flush failed", assertThrows(JsonException.class, g::flush).getCause().getMessage());
        assertThrows(JsonException.class, g::close);
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
