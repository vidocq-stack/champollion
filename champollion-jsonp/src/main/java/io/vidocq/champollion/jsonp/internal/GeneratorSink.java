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

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;

/**
 * Where a {@link ChampollionJsonGenerator} puts its output: a buffer of its own, handed to the
 * target in large writes. The buffer starts small and doubles up to a ceiling before it is
 * first handed over, so a small document costs a small buffer, and a large one reaches the
 * target in chunks of the ceiling.
 *
 * <p>Two targets: a {@link java.io.Writer} receives characters ({@link WriterSink}); an
 * {@link java.io.OutputStream} written in UTF-8 receives bytes the sink encodes itself, with
 * neither an {@code OutputStreamWriter} nor its encoder buffers in between
 * ({@link Utf8StreamSink}).</p>
 *
 * <p>A sink belongs to one generator, used by one thread at a time: no lock, no pooling, no
 * {@code ThreadLocal}.</p>
 */
abstract sealed class GeneratorSink<T extends Closeable & Flushable> permits WriterSink, Utf8StreamSink {

    /** The writer or stream the buffer is handed to. */
    final T out;
    private final int ceiling;
    /** Number of buffered units (characters or bytes) not yet handed to the target. */
    int pos;

    GeneratorSink(T out, int ceiling) {
        this.out = out;
        this.ceiling = ceiling;
    }

    /** Appends one ASCII character: the generator writes punctuation and white space this way, text through {@link #append}. */
    abstract void write(char c);

    /** Appends {@code s[off, off + len)}. The generator never splits a surrogate pair between two calls. */
    abstract void append(String s, int off, int len);

    final void append(String s) {
        append(s, 0, s.length());
    }

    /** Appends the decimal form of {@code v}, as {@link Long#toString(long)} writes it, without building that string. */
    final void appendLong(long v) {
        if (v == Long.MIN_VALUE) {
            append(Long.toString(v));
            return;
        }
        ensureRoom(MAX_LONG_CHARS);
        int end = pos + decimalLength(v);
        int i = end;
        long u = v < 0 ? -v : v;
        do {
            put(--i, (char) ('0' + (int) (u % 10)));
            u /= 10;
        } while (u != 0);
        if (v < 0) put(--i, '-');
        pos = end;
    }

    /** Stores the ASCII character {@code c} at {@code index} of the buffer, which has room for it. */
    abstract void put(int index, char c);

    /** The longest decimal form of a {@code long}: a minus sign and 19 digits. */
    static final int MAX_LONG_CHARS = 20;

    /** Number of characters of the decimal form of {@code v}, which is not {@link Long#MIN_VALUE}. */
    static int decimalLength(long v) {
        int n = 1;
        if (v < 0) {
            n = 2;
            v = -v;
        }
        long p = 10;
        for (int i = 1; i < 19; i++) {
            if (v < p) return n;
            p *= 10;
            n++;
        }
        return n;
    }

    /** Makes room for {@code n} more units, {@code n} at most the smallest buffer. */
    final void ensureRoom(int n) {
        while (capacity() - pos < n) makeRoom();
    }

    /** Capacity of the buffer, in units. */
    abstract int capacity();

    /** Replaces the buffer by a copy of {@code capacity} units. */
    abstract void resize(int capacity);

    /** Writes the {@link #pos} buffered units to the target. */
    abstract void writeBuffer() throws IOException;

    /** Flushes the target; the buffer must have been drained. */
    final void flushTarget() throws IOException {
        out.flush();
    }

    /** Flushes then closes the target; the buffer must have been drained. */
    final void closeTarget() throws IOException {
        out.flush();
        out.close();
    }

    /** Doubles the buffer while under the ceiling, otherwise hands it to the target. */
    final void makeRoom() {
        int capacity = capacity();
        if (capacity < ceiling) {
            resize(Math.min(capacity << 1, ceiling));
        } else {
            drain();
        }
    }

    /** Called once the generator is closed: a sink that borrowed a buffer gives it back. */
    void release() {
        // Nothing borrowed by default.
    }

    /** Hands the target what the buffer holds, in one write; an I/O error becomes a {@link JsonException}. */
    final void drain() {
        if (pos == 0) return;
        try {
            writeBuffer();
        } catch (IOException e) {
            throw new JsonException("I/O error", e);
        }
        pos = 0;
    }
}
