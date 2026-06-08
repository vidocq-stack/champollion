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
package io.vidocq.champollion.spi;

/**
 * Optional SPI implemented by Champollion {@link jakarta.json.stream.JsonGenerator}
 * instances to let static codegen tools bypass RFC 8259 §7 escaping for property
 * names known at compile time.
 *
 * <p>Contract: {@link #writeKeyRaw(String)} accepts a string already wrapped
 * in quotes and already escaped, and writes it as-is to the stream, handling
 * the separator comma and {@code :} like {@link
 * jakarta.json.stream.JsonGenerator#writeKey(String)}.</p>
 *
 * <p>Typical usage on the generated static binding side:</p>
 * <pre>{@code
 * private static final String K_x = "\"x\"";  // pre-encoded at compile time
 *
 * public void write(JsonGenerator g, Coord v) {
 *     g.writeStartObject();
 *     if (g instanceof RawJsonKeyWriter r) {
 *         r.writeKeyRaw(K_x);
 *         g.write(v.x());
 *     } else {
 *         g.write("x", v.x());
 *     }
 *     g.writeEnd();
 * }
 * }</pre>
 */
public interface RawJsonKeyWriter {

    /**
     * Writes a pre-encoded property name to the stream.
     *
     * @param preQuotedKey the {@code "<name>"} string with quotes and RFC 8259 §7
     *                     escaping already applied
     */
    void writeKeyRaw(String preQuotedKey);

    /**
     * Merged variant: the supplied fragment already includes the final
     * {@code :} ({@code "name":}). Lets the generator emit key + colon in one
     * {@code Writer.write(String)} call instead of two separate calls — useful
     * on hot loops with small ASCII keys.
     *
     * <p>Default implementation: delegates to {@link #writeKeyRaw(String)} by
     * extracting the fragment without the colon, then emits the colon separately.
     * Champollion implementations override this for the fast path.</p>
     *
     * @param preQuotedKeyWithColon the {@code "<name>":} string (quotes +
     *                              escaping + final colon)
     */
    default void writeKeyRawWithColon(String preQuotedKeyWithColon) {
        int n = preQuotedKeyWithColon.length();
        if (n == 0 || preQuotedKeyWithColon.charAt(n - 1) != ':') {
            throw new IllegalArgumentException("fragment must end with ':'");
        }
        writeKeyRaw(preQuotedKeyWithColon.substring(0, n - 1));
    }
}
