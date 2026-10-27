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

import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A REST entity is usually a small JSON document already in memory. Parsing it from an
 * InputStream built an InputStreamReader, whose decoder allocates an 8 KB byte buffer, for a body
 * of a few dozen bytes. A stream that says how many bytes it holds now gets a decoder buffer of
 * that size.
 */
class SmallBodyReadingTest {

    private static final byte[] BODY = "{\"title\":\"buy milk\",\"completed\":false}".getBytes(StandardCharsets.UTF_8);
    private static final ChampollionJsonProvider PROVIDER = new ChampollionJsonProvider();

    private static String parse(InputStream in) {
        StringBuilder events = new StringBuilder();
        try (JsonParser parser = PROVIDER.createParser(in)) {
            while (parser.hasNext()) {
                JsonParser.Event e = parser.next();
                events.append(e.name().charAt(0));
                if (e == JsonParser.Event.VALUE_STRING) events.append(parser.getString());
            }
        }
        return events.toString();
    }

    @Test
    void aSmallBodyInMemoryIsParsedWithoutAnEightKilobyteDecoderBuffer() {
        assertEquals("SKVbuy milkKVE", parse(new ByteArrayInputStream(BODY)));
        long perCall = bytesPerCall(() -> parse(new ByteArrayInputStream(BODY)));
        assertTrue(perCall < 4_096, "parsing a 40-byte body allocates " + perCall + " bytes per call");
    }

    @Test
    void multiByteCharactersAndAStreamOfUnknownLengthStillDecode() {
        byte[] accented = "[\"é\",\"€\",\"😀\"]".getBytes(StandardCharsets.UTF_8);
        assertEquals("SVéV€V😀E", parse(new ByteArrayInputStream(accented)));
        InputStream unknownLength = new ByteArrayInputStream(accented) {
            @Override
            public synchronized int available() {
                return 0;
            }
        };
        assertEquals("SVéV€V😀E", parse(unknownLength));
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
