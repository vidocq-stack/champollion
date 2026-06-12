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

import java.math.BigDecimal;

/**
 * RFC 8259 fast-path JSON tokenizer that reads a {@link String} directly via
 * {@link String#charAt} (P10.1). No {@code Reader}, no intermediate {@code char[]}
 * — saves about 1 KB of allocations per {@code fromJson(String)} and avoids
 * {@code String.getChars} copying.
 *
 * <p>P10.2 — {@link #next()} and all helpers are duplicated locally (not in the
 * abstract parent) so calls to {@link #read()} / {@link #peekRead()} remain
 * statically resolved in this final class, making them HotSpot-inlineable without
 * virtual dispatch.</p>
 *
 * <p>P12 — lazy pending value: scalar scans record a range into {@code src}
 * (immutable — no promotion ever needed) or accumulate into the scratch when
 * escapes are involved. Strings and numbers are scanned in-source in tight
 * loops with bulk position tracking, mirroring the Reader-mode P11 fast
 * paths. See the contract on {@link JsonTokenizer}.</p>
 */
public final class JsonStringTokenizer extends JsonTokenizer {

    private final String src;
    private final int srcLen;
    private int srcPos = 0;

    public JsonStringTokenizer(String src) {
        if (src == null) throw new IllegalArgumentException("src is null");
        this.src = src;
        this.srcLen = src.length();
    }

    @Override
    void close() { /* no-op: nothing to close */ }

    @Override
    protected char rangeChar(int i) {
        return src.charAt(valStart + i);
    }

    @Override
    protected String materializeRange() {
        return src.substring(valStart, valStart + valLen);
    }

    @Override
    protected BigDecimal rangeBigDecimal() {
        // String mode: BigDecimal lacks a CharSequence-range constructor;
        // materialize (and cache) the literal, small for sane numbers.
        return new BigDecimal(currentString());
    }

    private int read() {
        if (peek != NO_PEEK) {
            int c = peek;
            peek = NO_PEEK;
            track(c);
            return c;
        }
        if (srcPos >= srcLen) return -1;
        int c = src.charAt(srcPos++);
        track(c);
        return c;
    }

    private int peekRead() {
        if (peek != NO_PEEK) return peek;
        if (srcPos >= srcLen) { peek = -1; return -1; }
        peek = src.charAt(srcPos++);
        return peek;
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

    /** P12 — in-source scan with inlined position tracking (Reader P11 mirror). */
    private int skipWhitespace() {
        if (peek != NO_PEEK) {
            int c = consumePeek();
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return c;
        }
        while (srcPos < srcLen) {
            char ch = src.charAt(srcPos++);
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
        return -1;
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
     * P12 — in-source fast path: scans {@code src} directly until the closing
     * quote; an escape-free string is recorded as a source range — zero
     * allocation until {@code currentString()} is actually called. Strings
     * cannot contain raw newlines, so position tracking is bulk-applied.
     * Escapes fall back to the per-char loop, accumulating in the scratch.
     */
    private JsonToken readString() {
        if (peek == NO_PEEK) {
            int start = srcPos;
            int p = start;
            int end = srcLen;
            while (p < end) {
                char ch = src.charAt(p);
                if (ch == '"') {
                    int consumed = p - start + 1;
                    offset += consumed;
                    column += consumed;
                    srcPos = p + 1;
                    valueRange(start, p - start);
                    return JsonToken.STRING;
                }
                if (ch == '\\' || ch < 0x20) break;
                p++;
            }
            // Slow path: keep the already-scanned clean prefix.
            valueScratchStart();
            buffer.append(src, start, p);
            offset += p - start;
            column += p - start;
            srcPos = p;
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
     * P12/P13 — in-source fast path: the literal is scanned by RFC 8259 §6
     * grammar phases (int / frac / exp) in a SINGLE pass — the former separate
     * validation pass over the alphabet run is fused into the scan — and
     * recorded as a source range (end-of-input is a legal terminator in
     * String mode). The per-char slow loop only remains for the pending-peek
     * case and owns the precise error messages.
     */
    private JsonToken readNumber(int first) {
        // 'first' always comes from src when no peek is pending: read() took
        // it at src.charAt(srcPos - 1).
        if (peek == NO_PEEK && srcPos > 0 && src.charAt(srcPos - 1) == first) {
            final String s = src;
            int start = srcPos - 1;
            int q = srcPos;
            int end = srcLen;
            boolean valid = true;
            // integer part — 'first' is '-' or a digit
            if (first == '-') {
                if (q < end) {
                    char d = s.charAt(q);
                    if (d == '0') q++;
                    else if (d >= '1' && d <= '9') {
                        q++;
                        while (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') q++;
                    } else valid = false;
                } else valid = false; // bare "-" at end of input
            } else if (first != '0') {
                while (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') q++;
            }
            // frac part
            if (valid && q < end && s.charAt(q) == '.') {
                q++;
                if (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') {
                    q++;
                    while (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') q++;
                } else valid = false;
            }
            // exp part
            if (valid && q < end && (s.charAt(q) == 'e' || s.charAt(q) == 'E')) {
                q++;
                if (q < end && (s.charAt(q) == '+' || s.charAt(q) == '-')) q++;
                if (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') {
                    q++;
                    while (q < end && s.charAt(q) >= '0' && s.charAt(q) <= '9') q++;
                } else valid = false;
            }
            // grammar complete, but a trailing number-alphabet char means the
            // maximal run is invalid (e.g. "01", "1.2.3", "1e2e3")
            if (valid && q < end) {
                char t = s.charAt(q);
                if ((t >= '0' && t <= '9') || t == '.' || t == 'e' || t == 'E' || t == '+' || t == '-') {
                    valid = false;
                }
            }
            if (!valid) {
                // error message = the maximal alphabet run, as before
                int r = q;
                while (r < end) {
                    char ch = s.charAt(r);
                    if ((ch >= '0' && ch <= '9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') r++;
                    else break;
                }
                throw error("Invalid JSON number: " + s.substring(start, r));
            }
            int consumed = q - srcPos; // 'first' was already tracked
            offset += consumed;
            column += consumed;
            srcPos = q;
            valueRange(start, q - start);
            return JsonToken.NUMBER;
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

}
