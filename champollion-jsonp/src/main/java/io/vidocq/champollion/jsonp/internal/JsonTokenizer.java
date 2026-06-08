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

/**
 * Abstract pull-based JSON tokenizer, strict RFC 8259.
 *
 * <p>P10.2 — each final subclass duplicates {@link #next()} and its helpers so
 * HotSpot can statically inline {@code read()}/{@code peekRead()} (monomorphic
 * because they are called <em>within</em> a single final class). The parent only
 * provides:</p>
 * <ul>
 *   <li>shared fields (peek, line/column/offset, StringBuilder buffer) ;</li>
 *   <li>the {@link #next()} and {@link #close()} signatures ;</li>
 *   <li>{@link #line()}/{@link #column()}/{@link #offset()} for diagnostics ;</li>
 *   <li>an exception factory {@link #error(String)} and a shared helper
 *       {@link #describe(int)} across implementations.</li>
 * </ul>
 *
 * <p>No {@code synchronized}, no {@code ThreadLocal} —
 * virtual-thread-friendly.</p>
 */
public abstract class JsonTokenizer {

    static final int NO_PEEK = -2;

    protected final StringBuilder buffer = new StringBuilder(64);

    /** Pre-read character (for 1-char lookahead), or {@code -2} if none. */
    protected int peek = NO_PEEK;
    protected long line = 1;
    protected long column = 0;
    protected long offset = 0;

    JsonTokenizer() {}

    /** Reads the next token. RFC 8259 §2 whitespace is skipped. */
    public abstract JsonToken next();

    /** Closes the underlying source (Reader). No-op in String mode. */
    abstract void close();

    public final long line() { return line; }
    public final long column() { return column; }
    public final long offset() { return offset; }

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
