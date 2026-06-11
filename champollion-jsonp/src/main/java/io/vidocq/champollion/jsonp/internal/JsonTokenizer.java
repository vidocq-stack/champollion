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
 * Abstract pull-based JSON tokenizer, strict RFC 8259.
 *
 * <p>P10.2 — each final subclass duplicates {@link #next()} and its helpers so
 * HotSpot can statically inline {@code read()}/{@code peekRead()} (monomorphic
 * because they are called <em>within</em> a single final class).</p>
 *
 * <p>P12 — lazy pending value. After {@link #next()} returns
 * {@link JsonToken#STRING} or {@link JsonToken#NUMBER}, the scalar value is
 * <em>not</em> materialized: the tokenizer records it either as a range into
 * the source buffer ({@code valStart}/{@code valLen}) or, when escapes or a
 * buffer boundary were involved, as a copy in the shared {@code buffer}
 * scratch ({@code valScratch}). {@link #currentString()},
 * {@link #currentBigDecimal()}, {@link #currentIntegral()} and
 * {@link #currentLong()} materialize on demand (the String is cached).</p>
 *
 * <p><b>Pending-value contract</b>: the value stays readable until the next
 * {@code STRING}/{@code NUMBER} token scan begins — scanning separators,
 * keywords or whitespace (including across a {@code Reader} refill, which
 * promotes a live range into the scratch) never invalidates it. This matches
 * the parser's look-ahead behaviour: {@code hasNext()} may scan separator
 * tokens ahead of an unread value.</p>
 *
 * <p>No {@code synchronized}, no {@code ThreadLocal} —
 * virtual-thread-friendly.</p>
 */
public abstract class JsonTokenizer {

    static final int NO_PEEK = -2;

    /** Shared scratch: slow-path value accumulator and refill-promotion target. */
    protected final StringBuilder buffer = new StringBuilder(64);

    /** Pre-read character (for 1-char lookahead), or {@code -2} if none. */
    protected int peek = NO_PEEK;
    protected long line = 1;
    protected long column = 0;
    protected long offset = 0;

    /** Pending value range start in the subclass source, or -1 if none/scratch. */
    protected int valStart = -1;
    /** Pending value length (range and scratch modes alike). */
    protected int valLen;
    /** True when the pending value lives in {@link #buffer} (escapes, refill promotion). */
    protected boolean valScratch;
    /** Cached materialization of the pending value, or null. */
    protected String valMaterialized;

    JsonTokenizer() {}

    /** Reads the next token. RFC 8259 §2 whitespace is skipped. */
    public abstract JsonToken next();

    /** Closes the underlying source (Reader). No-op in String mode. */
    abstract void close();

    public final long line() { return line; }
    public final long column() { return column; }
    public final long offset() { return offset; }

    /** Char {@code i} of the pending value range in the subclass source. */
    protected abstract char rangeChar(int i);

    /** Materializes the pending value range from the subclass source. */
    protected abstract String materializeRange();

    /** {@code BigDecimal} straight from the pending value range (no String). */
    protected abstract BigDecimal rangeBigDecimal();

    /** Materializes (and caches) the pending scalar value. */
    public final String currentString() {
        String m = valMaterialized;
        if (m == null) {
            m = valScratch ? buffer.toString() : materializeRange();
            valMaterialized = m;
        }
        return m;
    }

    /** True when the pending number literal has no fraction and no exponent. */
    public final boolean currentIntegral() {
        for (int i = 0; i < valLen; i++) {
            char c = valueChar(i);
            if (c == '.' || c == 'e' || c == 'E') return false;
        }
        return true;
    }

    /** Pending number as {@code BigDecimal} — no String on the range fast path. */
    public final BigDecimal currentBigDecimal() {
        if (valMaterialized != null) return new BigDecimal(valMaterialized);
        if (valScratch) return new BigDecimal(currentString());
        return rangeBigDecimal();
    }

    /**
     * Pending number as {@code long}: direct digit parse when integral and at
     * most 18 digits, otherwise {@code currentBigDecimal().longValue()} (same
     * low-order-bits semantics as the spec accessors).
     */
    public final long currentLong() {
        if (!currentIntegral()) return currentBigDecimal().longValue();
        boolean neg = valueChar(0) == '-';
        int i = neg ? 1 : 0;
        if (valLen - i > 18) return currentBigDecimal().longValue();
        long v = 0;
        for (; i < valLen; i++) {
            v = v * 10 + (valueChar(i) - '0');
        }
        return neg ? -v : v;
    }

    private char valueChar(int i) {
        if (valMaterialized != null) return valMaterialized.charAt(i);
        return valScratch ? buffer.charAt(i) : rangeChar(i);
    }

    /** Registers a freshly scanned value as a source range. */
    protected final void valueRange(int start, int len) {
        valStart = start;
        valLen = len;
        valScratch = false;
        valMaterialized = null;
    }

    /** Starts a slow-path scan: the value accumulates in {@link #buffer}. */
    protected final void valueScratchStart() {
        buffer.setLength(0);
        valStart = -1;
        valScratch = true;
        valMaterialized = null;
    }

    /** Ends a slow-path scan ({@link #buffer} holds the full value). */
    protected final void valueScratchEnd() {
        valLen = buffer.length();
    }

    protected final void track(int c) {
        if (c == -1) return;
        offset++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
    }

    protected final jakarta.json.stream.JsonParsingException error(String message) {
        return new jakarta.json.stream.JsonParsingException(
                message + " (at line " + line + ", column " + column + ", offset " + offset + ")",
                new SimpleLocation(line, column, offset));
    }

    private record SimpleLocation(long line, long column, long offset) implements jakarta.json.stream.JsonLocation {
        @Override public long getLineNumber() { return line; }
        @Override public long getColumnNumber() { return column; }
        @Override public long getStreamOffset() { return offset; }
    }

    protected static String describe(int c) {
        if (c == -1) return "<EOF>";
        if (c >= 0x20 && c < 0x7F) return "'" + (char) c + "'";
        return String.format("U+%04X", c);
    }

    protected static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        return -1;
    }
}
