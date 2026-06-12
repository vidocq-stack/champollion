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

import jakarta.json.stream.JsonParsingException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Alignment matrix for the string-scan fast paths (P13 SWAR). The word-at-a-time
 * scanner processes 4 chars per 64-bit load, so every special character
 * (closing quote, backslash, raw control char) must be detected at EVERY
 * offset modulo the word size, with arbitrary prefix lengths, across refill
 * boundaries, and amid non-ASCII lanes (high char values must never mask or
 * fake a detection). These tests are behavioural characterization: they must
 * hold for the per-char loop and for any accelerated scan alike (RFC 8259 §7).
 */
class JsonTokenizerScanBoundaryTest {

    /** Reader returning at most {@code chunk} chars per read() — forces refills
     *  at chosen positions regardless of the tokenizer buffer size. */
    private static final class ChunkedReader extends Reader {
        private final String src;
        private final int chunk;
        private int pos = 0;
        ChunkedReader(String src, int chunk) { this.src = src; this.chunk = chunk; }
        @Override public int read(char[] cbuf, int off, int len) throws IOException {
            if (pos >= src.length()) return -1;
            int n = Math.min(Math.min(len, chunk), src.length() - pos);
            src.getChars(pos, pos + n, cbuf, off);
            pos += n;
            return n;
        }
        @Override public void close() {}
    }

    private static String roundTripString(JsonTokenizer t) {
        assertEquals(JsonToken.STRING, t.next());
        String v = t.currentString();
        assertEquals(JsonToken.EOF, t.next());
        return v;
    }

    private static void assertStringValue(String expected, String jsonLiteral) {
        // Reader mode (in-buffer scan), String mode (in-source scan), and
        // chunked Reader mode (scan interrupted by refills at odd positions).
        assertEquals(expected, roundTripString(new JsonReaderTokenizer(new StringReader(jsonLiteral))),
                "reader mode: " + jsonLiteral);
        assertEquals(expected, roundTripString(new JsonStringTokenizer(jsonLiteral)),
                "string mode: " + jsonLiteral);
        for (int chunk : new int[]{1, 3, 7}) {
            assertEquals(expected, roundTripString(new JsonReaderTokenizer(new ChunkedReader(jsonLiteral, chunk))),
                    "chunked(" + chunk + ") mode: " + jsonLiteral);
        }
    }

    @Test
    void closingQuote_atEveryWordAlignment() {
        for (int pad = 0; pad <= 9; pad++) {
            String body = "x".repeat(pad);
            assertStringValue(body, '"' + body + '"');
        }
    }

    @Test
    void escape_atEveryWordAlignment() {
        for (int pad = 0; pad <= 9; pad++) {
            String prefix = "y".repeat(pad);
            assertStringValue(prefix + "\nz", '"' + prefix + "\\nz\"");
            assertStringValue(prefix + "\"z", '"' + prefix + "\\\"z\"");
            assertStringValue(prefix + "\\z", '"' + prefix + "\\\\z\"");
        }
    }

    @Test
    void unicodeEscape_atEveryWordAlignment() {
        for (int pad = 0; pad <= 9; pad++) {
            String prefix = "u".repeat(pad);
            assertStringValue(prefix + "é", '"' + prefix + "\\u00E9\"");
        }
    }

    @Test
    void rawControlChar_rejectedAtEveryWordAlignment() {
        for (int pad = 0; pad <= 9; pad++) {
            for (char ctrl : new char[]{0x00, 0x01, 0x09, 0x0A, 0x1F}) {
                String json = '"' + "c".repeat(pad) + ctrl + "tail\"";
                assertThrows(JsonParsingException.class,
                        () -> roundTripString(new JsonReaderTokenizer(new StringReader(json))),
                        "reader mode must reject U+%04X after pad %d".formatted((int) ctrl, pad));
                assertThrows(JsonParsingException.class,
                        () -> roundTripString(new JsonStringTokenizer(json)),
                        "string mode must reject U+%04X after pad %d".formatted((int) ctrl, pad));
            }
        }
    }

    @Test
    void space_isNotAControlChar() {
        // 0x20 is the boundary value of the < 0x20 rejection — must pass.
        assertStringValue("a b", "\"a b\"");
    }

    @Test
    void nonAsciiLanes_neverFakeOrMaskADetection() {
        // High char values exercise the SWAR borrow/overflow edge cases:
        // é (0x00E9), CJK (0x4E2D), FF chars (0xFFFD), surrogate pairs.
        for (int pad = 0; pad <= 4; pad++) {
            String prefix = "é中�😀".repeat(2);
            String body = "x".repeat(pad) + prefix + "end";
            assertStringValue(body, '"' + body + '"');
            // and a quote right after the non-ASCII run
            assertStringValue("x".repeat(pad) + prefix, '"' + "x".repeat(pad) + prefix + '"');
        }
    }

    @Test
    void longStrings_crossTheInternalBufferBoundary() {
        // Longer than JsonReaderTokenizer.BUF_SIZE: the in-buffer fast path
        // must hand over to the scratch slow path without losing characters.
        for (int len : new int[]{JsonReaderTokenizer.BUF_SIZE - 1,
                                 JsonReaderTokenizer.BUF_SIZE,
                                 JsonReaderTokenizer.BUF_SIZE + 1,
                                 JsonReaderTokenizer.BUF_SIZE * 3 + 5}) {
            String body = "abcdefgh".repeat((len / 8) + 1).substring(0, len);
            assertStringValue(body, '"' + body + '"');
        }
    }

    @Test
    void unterminatedString_failsAtEveryAlignment() {
        for (int pad = 0; pad <= 9; pad++) {
            String json = '"' + "q".repeat(pad);
            assertThrows(JsonParsingException.class,
                    () -> roundTripString(new JsonReaderTokenizer(new StringReader(json))));
            assertThrows(JsonParsingException.class,
                    () -> roundTripString(new JsonStringTokenizer(json)));
        }
    }
}
