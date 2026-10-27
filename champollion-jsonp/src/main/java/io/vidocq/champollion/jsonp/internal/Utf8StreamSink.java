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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Encodes UTF-8 (RFC 3629) straight into a byte buffer handed to an {@link OutputStream}: the
 * default encoding of a generator on a stream ({@code JsonProvider#createGenerator(OutputStream)},
 * RFC 8259 §8.1), and the one of a REST response written by {@code Jsonb.toJson(Object, OutputStream)}.
 *
 * <p>The bytes are those an {@code OutputStreamWriter} in UTF-8 produced: a surrogate pair
 * becomes one four-byte sequence, and a lone surrogate becomes {@code '?'}, the replacement of
 * the JDK UTF-8 encoder. The generator hands every run of unescaped characters in one
 * {@link #append} call, so a pair never straddles two calls.</p>
 */
final class Utf8StreamSink extends GeneratorSink {

    private static final int INITIAL = 128;
    private static final int CEILING = 4096;
    /** The longest UTF-8 sequence, that of a supplementary code point. */
    private static final int MAX_SEQUENCE = 4;
    private static final byte REPLACEMENT = '?';

    private final OutputStream out;
    private byte[] buf = new byte[INITIAL];

    Utf8StreamSink(OutputStream out) {
        super(CEILING);
        this.out = out;
    }

    @Override void write(char c) {
        if (c < 0x80) {
            if (pos == buf.length) makeRoom();
            buf[pos++] = (byte) c;
        } else {
            encode(c, (char) 0);
        }
    }

    @Override void append(String s, int off, int len) {
        int end = off + len;
        while (off < end) {
            if (pos == buf.length) makeRoom();
            off = copyAscii(s, off, Math.min(end, off + buf.length - pos));
            if (off < end && s.charAt(off) >= 0x80) {
                char next = off + 1 < end ? s.charAt(off + 1) : 0;
                off += encode(s.charAt(off), next);
            }
        }
    }

    /** Copies the ASCII characters of {@code s} from {@code off} up to {@code stop}, one byte each; returns where it stopped. */
    private int copyAscii(String s, int off, int stop) {
        byte[] b = buf;
        int p = pos;
        while (off < stop) {
            char c = s.charAt(off);
            if (c >= 0x80) break;
            b[p++] = (byte) c;
            off++;
        }
        pos = p;
        return off;
    }

    /**
     * Encodes the non-ASCII {@code c}, given the character that follows it in the same run
     * ({@code 0} when none), and returns how many characters it consumed.
     */
    private int encode(char c, char next) {
        if (buf.length - pos < MAX_SEQUENCE) makeRoom();
        if (c < 0x800) {
            buf[pos++] = (byte) (0xC0 | (c >> 6));
            buf[pos++] = (byte) (0x80 | (c & 0x3F));
            return 1;
        }
        if (!Character.isSurrogate(c)) {
            buf[pos++] = (byte) (0xE0 | (c >> 12));
            buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
            buf[pos++] = (byte) (0x80 | (c & 0x3F));
            return 1;
        }
        if (Character.isHighSurrogate(c) && Character.isLowSurrogate(next)) {
            int cp = Character.toCodePoint(c, next);
            buf[pos++] = (byte) (0xF0 | (cp >> 18));
            buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            return 2;
        }
        buf[pos++] = REPLACEMENT;
        return 1;
    }

    @Override int capacity() {
        return buf.length;
    }

    @Override void resize(int capacity) {
        buf = Arrays.copyOf(buf, capacity);
    }

    @Override void writeBuffer() throws IOException {
        out.write(buf, 0, pos);
    }

    @Override void flushTarget() throws IOException {
        out.flush();
    }

    @Override void closeTarget() throws IOException {
        out.flush();
        out.close();
    }
}
