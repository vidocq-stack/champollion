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

import jakarta.json.JsonException;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;

/**
 * RFC 8259 JSON tokenizer that reads a {@link Reader} through a pre-allocated
 * {@code char[BUF_SIZE]} buffer (P1).
 *
 * <p>P10.2 — {@link #next()} and all helpers ({@code skipWhitespace},
 * {@code readKeyword}, {@code readString}, {@code readEscape},
 * {@code readNumber}…) are duplicated locally (not in the abstract parent) so
 * calls to {@link #read()}/{@link #peekRead()} remain <em>statically resolved</em>
 * in the same final class, and therefore HotSpot-inlineable without virtual
 * dispatch.</p>
 *
 * <p>P12 — lazy pending value: scalar scans record a {@code buf} range (fast
 * path) or accumulate into the scratch (escapes / boundary crossings) instead
 * of materializing a String. {@link #refill()} <em>promotes</em> a live
 * un-materialized range into the scratch before clobbering {@code buf}, so the
 * pending value survives separator/keyword/whitespace scans across buffer
 * boundaries (the parser's look-ahead case). See the contract on
 * {@link JsonTokenizer}.</p>
 */
public final class JsonReaderTokenizer extends JsonTokenizer {

    /** Size of the read {@code char[]} buffer. */
    public static final int BUF_SIZE = 512;

    private final Reader reader;
    private final char[] buf = new char[BUF_SIZE];
    private int bufPos = 0;
    private int bufEnd = 0;
    private boolean eof = false;

    public JsonReaderTokenizer(Reader reader) {
        if (reader == null) throw new IllegalArgumentException("reader is null");
        this.reader = reader;
    }

    @Override
    void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new JsonException("I/O error closing reader", e);
        }
    }

    @Override
    protected char rangeChar(int i) {
        return buf[valStart + i];
    }

    @Override
    protected String materializeRange() {
        return new String(buf, valStart, valLen);
    }

    @Override
    protected BigDecimal rangeBigDecimal() {
        return new BigDecimal(buf, valStart, valLen);
    }

    private int read() {
        if (peek != NO_PEEK) {
            int c = peek;
            peek = NO_PEEK;
            track(c);
            return c;
        }
        if (bufPos >= bufEnd) {
            if (eof) return -1;
            refill();
            if (bufEnd == 0) return -1;
        }
        int c = buf[bufPos++];
        track(c);
        return c;
    }

    private int peekRead() {
        if (peek != NO_PEEK) return peek;
        if (bufPos >= bufEnd) {
            if (eof) return -1;
            refill();
            if (bufEnd == 0) { peek = -1; return -1; }
        }
        peek = buf[bufPos++];
        return peek;
    }

    private void refill() {
        // P12 — promote a live un-materialized pending range into the scratch
        // before the buffer content is overwritten. Never fires during a
        // slow-path scalar scan (those run in scratch mode already).
        if (valStart >= 0 && !valScratch && valMaterialized == null) {
            buffer.setLength(0);
            buffer.append(buf, valStart, valLen);
            valStart = -1;
            valScratch = true;
        }
        try {
            int n = reader.read(buf, 0, BUF_SIZE);
            if (n <= 0) { eof = true; bufEnd = 0; bufPos = 0; }
            else { bufEnd = n; bufPos = 0; }
        } catch (IOException e) {
            throw new JsonException("I/O error reading JSON", e);
        }
    }

    private int consumePeek() {
        int c = peek;
        peek = NO_PEEK;
        track(c);
        return c;
    }

    @Override
    public JsonToken next() {
        int c = skipWhitespace();
        return switch (c) {
            case -1 -> JsonToken.EOF;
            case '{' -> JsonToken.START_OBJECT;
            case '}' -> JsonToken.END_OBJECT;
            case '[' -> JsonToken.START_ARRAY;
            case ']' -> JsonToken.END_ARRAY;
            case ':' -> JsonToken.NAME_SEPARATOR;
            case ',' -> JsonToken.VALUE_SEPARATOR;
            case '"' -> readString();
            case 't' -> readKeyword("true", JsonToken.TRUE);
            case 'f' -> readKeyword("false", JsonToken.FALSE);
            case 'n' -> readKeyword("null", JsonToken.NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber(c);
                }
                throw error("Unexpected character: " + describe(c));
            }
        };
    }

    /**
     * P11 — in-buffer scan: whitespace is consumed directly from {@code buf}
     * in a tight loop with inlined position tracking, instead of one
     * {@code read()} call (peek check + bounds check + {@code track()}) per
     * character. The pending peek slot is honoured on entry.
     */
    private int skipWhitespace() {
        if (peek != NO_PEEK) {
            int c = consumePeek();
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return c;
        }
        while (true) {
            while (bufPos < bufEnd) {
                char ch = buf[bufPos++];
                offset++;
                if (ch == '\n') {
                    line++;
                    column = 0;
                    continue;
                }
                column++;
                if (ch == ' ' || ch == '\t' || ch == '\r') continue;
                return ch;
            }
            if (eof) return -1;
            refill();
            if (bufEnd == 0) return -1;
        }
    }

    private JsonToken readKeyword(String expected, JsonToken token) {
        for (int i = 1; i < expected.length(); i++) {
            int c = read();
            if (c != expected.charAt(i)) {
                throw error("Expected '" + expected + "', mismatch at character " + (i + 1));
            }
        }
        return token;
    }

    /**
     * P11/P12 — in-buffer fast path: scans {@code buf} directly until the
     * closing quote. When the whole string sits in the current buffer with no
     * escape, the value is recorded as a buffer <em>range</em> — zero
     * allocation until {@code currentString()} is actually called. Position
     * tracking is bulk-applied: a JSON string cannot contain a raw newline
     * (control characters are rejected), so {@code column}/{@code offset}
     * advance by the scanned length exactly. Escapes and buffer-boundary
     * crossings fall back to the per-char loop below, accumulating in the
     * scratch.
     */
    private JsonToken readString() {
        // Fast path requires direct buffer indexing: a pending peek (only set
        // by number scanning, never active here after next()'s read()) or an
        // exhausted buffer goes straight to the slow loop.
        if (peek == NO_PEEK) {
            int start = bufPos;
            int p = start;
            int end = bufEnd;
            char[] b = buf;
            while (p < end) {
                char ch = b[p];
                if (ch == '"') {
                    int consumed = p - start + 1;
                    offset += consumed;
                    column += consumed;
                    bufPos = p + 1;
                    valueRange(start, p - start);
                    return JsonToken.STRING;
                }
                if (ch == '\\' || ch < 0x20) break;
                p++;
            }
            // Slow path: keep the already-scanned clean prefix.
            valueScratchStart();
            buffer.append(b, start, p - start);
            offset += p - start;
            column += p - start;
            bufPos = p;
        } else {
            valueScratchStart();
        }
        while (true) {
            int c = read();
            if (c == -1) throw error("Unterminated string literal");
            if (c == '"') {
                valueScratchEnd();
                return JsonToken.STRING;
            }
            if (c == '\\') { buffer.append(readEscape()); continue; }
            if (c < 0x20) {
                throw error("Unescaped control character U+" + String.format("%04X", c) + " in string");
            }
            buffer.append((char) c);
        }
    }

    private char readEscape() {
        int c = read();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            case -1 -> throw error("Unterminated escape sequence");
            default -> throw error("Invalid escape character: " + describe(c));
        };
    }

    private char readUnicodeEscape() {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int c = read();
            if (c == -1) throw error("Unterminated unicode escape");
            int d = hexDigit(c);
            if (d < 0) throw error("Invalid hex digit in unicode escape: " + describe(c));
            code = (code << 4) | d;
        }
        return (char) code;
    }

    /**
     * P11/P12 — in-buffer fast path: when the whole literal lies in the
     * current buffer (its terminator included), the maximal number-alphabet
     * run is scanned with direct indexing, validated against the RFC 8259
     * number grammar in one linear pass, and recorded as a buffer range — no
     * String until a number accessor is actually called. Numbers cannot
     * contain newlines, so position tracking is bulk-applied. Buffer
     * boundaries (and the rare pending-peek case) fall back to the original
     * per-char loop, which also owns the precise error messages.
     */
    private JsonToken readNumber(int first) {
        // 'first' always comes from this buffer when no refill intervened:
        // both the direct read and the peek path took it at buf[bufPos-1].
        if (peek == NO_PEEK && bufPos > 0 && buf[bufPos - 1] == first) {
            char[] b = buf;
            int end = bufEnd;
            int start = bufPos - 1;
            int q = bufPos;
            while (q < end) {
                char ch = b[q];
                if ((ch >= '0' && ch <= '9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    q++;
                } else {
                    break;
                }
            }
            if (q < end) { // terminator inside the buffer → the literal is complete
                if (!isValidRfc8259Number(b, start, q - start)) {
                    throw error("Invalid JSON number: " + new String(b, start, q - start));
                }
                int consumed = q - bufPos; // 'first' was already tracked
                offset += consumed;
                column += consumed;
                bufPos = q;
                valueRange(start, q - start);
                return JsonToken.NUMBER;
            }
        }
        valueScratchStart();
        buffer.append((char) first);

        int c = first;
        if (c == '-') {
            c = read();
            if (c == -1) throw error("Unexpected end after '-'");
            if (c < '0' || c > '9') throw error("Expected digit after '-', got " + describe(c));
            buffer.append((char) c);
        }

        if (c == '0') {
            int n = peekRead();
            if (n >= '0' && n <= '9') throw error("Leading zeros are not allowed in JSON numbers");
        } else {
            while (true) {
                int n = peekRead();
                if (n >= '0' && n <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

        int n = peekRead();
        if (n == '.') {
            buffer.append((char) consumePeek());
            int d = read();
            if (d < '0' || d > '9') throw error("Expected digit after '.', got " + describe(d));
            buffer.append((char) d);
            while (true) {
                int x = peekRead();
                if (x >= '0' && x <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

        n = peekRead();
        if (n == 'e' || n == 'E') {
            buffer.append((char) consumePeek());
            int s = peekRead();
            if (s == '+' || s == '-') buffer.append((char) consumePeek());
            int d = read();
            if (d < '0' || d > '9') throw error("Expected digit in exponent, got " + describe(d));
            buffer.append((char) d);
            while (true) {
                int x = peekRead();
                if (x >= '0' && x <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

        valueScratchEnd();
        return JsonToken.NUMBER;
    }

    /** Strict RFC 8259 §6 number grammar, one linear pass over the literal. */
    private static boolean isValidRfc8259Number(char[] a, int off, int len) {
        int i = off;
        int n = off + len;
        if (a[i] == '-') i++;
        if (i == n) return false;
        char c = a[i];
        if (c == '0') {
            i++;
        } else if (c >= '1' && c <= '9') {
            i++;
            while (i < n && isDigit(a[i])) i++;
        } else {
            return false;
        }
        if (i < n && a[i] == '.') {
            i++;
            if (i == n || !isDigit(a[i])) return false;
            while (i < n && isDigit(a[i])) i++;
        }
        if (i < n && (a[i] == 'e' || a[i] == 'E')) {
            i++;
            if (i < n && (a[i] == '+' || a[i] == '-')) i++;
            if (i == n || !isDigit(a[i])) return false;
            while (i < n && isDigit(a[i])) i++;
        }
        return i == n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
