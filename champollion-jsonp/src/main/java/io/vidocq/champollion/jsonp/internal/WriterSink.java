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
import java.io.Writer;
import java.util.Arrays;

/**
 * Buffers characters for a {@link Writer}: the target receives one write per full buffer of
 * 1024 characters and one at the end. A {@code BufferedWriter} in between used to take a lock
 * on every call, which was the hottest frame of the profile of the REST benchmark
 * (VidocqTools/vidocq-benchmarks).
 */
final class WriterSink extends GeneratorSink {

    private static final int INITIAL = 128;
    private static final int CEILING = 1024;

    private final Writer out;
    private char[] buf = new char[INITIAL];

    WriterSink(Writer out) {
        super(CEILING);
        this.out = out;
    }

    @Override void write(char c) {
        if (pos == buf.length) makeRoom();
        buf[pos++] = c;
    }

    @Override void append(String s, int off, int len) {
        int end = off + len;
        while (off < end) {
            if (pos == buf.length) makeRoom();
            int n = Math.min(end - off, buf.length - pos);
            s.getChars(off, off + n, buf, pos);
            pos += n;
            off += n;
        }
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
