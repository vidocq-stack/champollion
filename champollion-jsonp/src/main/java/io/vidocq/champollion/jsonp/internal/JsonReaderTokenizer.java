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
            case -1 -> JsonToken.Eof.INSTANCE;
            case '{' -> JsonToken.StartObject.INSTANCE;
            case '}' -> JsonToken.EndObject.INSTANCE;
            case '[' -> JsonToken.StartArray.INSTANCE;
            case ']' -> JsonToken.EndArray.INSTANCE;
            case ':' -> JsonToken.NameSeparator.INSTANCE;
            case ',' -> JsonToken.ValueSeparator.INSTANCE;
            case '"' -> readString();
            case 't' -> readKeyword("true", JsonToken.True.INSTANCE);
            case 'f' -> readKeyword("false", JsonToken.False.INSTANCE);
            case 'n' -> readKeyword("null", JsonToken.Null.INSTANCE);
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
     * P11 — in-buffer fast path: scans {@code buf} directly until the closing
     * quote. When the whole string sits in the current buffer with no escape,
     * the value is materialized with a single {@code new String(buf, start, len)}
     * — no StringBuilder, no per-char {@code read()}/{@code track()}. Position
     * tracking is bulk-applied: a JSON string cannot contain a raw newline
     * (control characters are rejected), so {@code column}/{@code offset}
     * advance by the scanned length exactly. Escapes and buffer-boundary
     * crossings fall back to the per-char loop below.
     */
    private JsonToken.StringToken readString() {
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
                    String s = new String(b, start, p - start);
                    int consumed = p - start + 1;
                    offset += consumed;
                    column += consumed;
                    bufPos = p + 1;
                    return new JsonToken.StringToken(s);
                }
                if (ch == '\\' || ch < 0x20) break;
                p++;
            }
            // Slow path: keep the already-scanned clean prefix.
            buffer.setLength(0);
            buffer.append(b, start, p - start);
            offset += p - start;
            column += p - start;
            bufPos = p;
        } else {
            buffer.setLength(0);
        }
        while (true) {
            int c = read();
            if (c == -1) throw error("Unterminated string literal");
            if (c == '"') return new JsonToken.StringToken(buffer.toString());
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
     * P11 — in-buffer fast path: when the whole literal lies in the current
     * buffer (its terminator included), the maximal number-alphabet run is
     * scanned with direct indexing, materialized with a single
     * {@code new String(buf, start, len)} and validated against the RFC 8259
     * number grammar in one linear pass — no per-char peek/track. Numbers
     * cannot contain newlines, so position tracking is bulk-applied. Buffer
     * boundaries (and the rare pending-peek case) fall back to the original
     * per-char loop, which also owns the precise error messages.
     */
    private JsonToken.NumberToken readNumber(int first) {
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
                String lit = new String(b, start, q - start);
                if (!isValidRfc8259Number(lit)) {
                    throw error("Invalid JSON number: " + lit);
                }
                int consumed = q - bufPos; // 'first' was already tracked
                offset += consumed;
                column += consumed;
                bufPos = q;
                return new JsonToken.NumberToken(lit);
            }
        }
        buffer.setLength(0);
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

        return new JsonToken.NumberToken(buffer.toString());
    }

    /** Strict RFC 8259 §6 number grammar, one linear pass over the literal. */
    private static boolean isValidRfc8259Number(String s) {
        int i = 0;
        int n = s.length();
        if (s.charAt(i) == '-') i++;
        if (i == n) return false;
        char c = s.charAt(i);
        if (c == '0') {
            i++;
        } else if (c >= '1' && c <= '9') {
            i++;
            while (i < n && isDigit(s.charAt(i))) i++;
        } else {
            return false;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            if (i == n || !isDigit(s.charAt(i))) return false;
            while (i < n && isDigit(s.charAt(i))) i++;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (i == n || !isDigit(s.charAt(i))) return false;
            while (i < n && isDigit(s.charAt(i))) i++;
        }
        return i == n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
