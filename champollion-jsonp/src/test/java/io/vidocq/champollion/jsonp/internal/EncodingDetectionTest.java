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
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RFC 8259 §8.1: a JSON text read from bytes is decoded in the encoding its byte order mark names,
 * or, without one, in the encoding the zero bytes among its first four tell.
 */
class EncodingDetectionTest {

    private static final ChampollionJsonProvider PROVIDER = new ChampollionJsonProvider();
    private static final String TEXT = "[\"é€\"]";

    private static String firstString(InputStream in) {
        try (JsonParser parser = PROVIDER.createParser(in)) {
            parser.next();
            parser.next();
            return parser.getString();
        }
    }

    private static byte[] encoded(String charset, String bom) {
        byte[] text = TEXT.getBytes(Charset.forName(charset));
        byte[] mark = java.util.HexFormat.of().parseHex(bom);
        byte[] all = new byte[mark.length + text.length];
        System.arraycopy(mark, 0, all, 0, mark.length);
        System.arraycopy(text, 0, all, mark.length, text.length);
        return all;
    }

    @ParameterizedTest
    @CsvSource({
            "UTF-8, ''", "UTF-8, efbbbf",
            "UTF-16BE, ''", "UTF-16BE, feff",
            "UTF-16LE, ''", "UTF-16LE, fffe",
            "UTF-32BE, ''", "UTF-32BE, 0000feff",
            "UTF-32LE, ''", "UTF-32LE, fffe0000"})
    void eachEncodingIsRecognisedWithOrWithoutItsByteOrderMark(String charset, String bom) {
        assertEquals("é€", firstString(new ByteArrayInputStream(encoded(charset, bom))));
    }

    @Test
    void aTextShorterThanFourBytesIsReadAsUtf8() {
        try (JsonParser parser = PROVIDER.createParser(new ByteArrayInputStream("1".getBytes(Charset.forName("UTF-8"))))) {
            assertEquals(JsonParser.Event.VALUE_NUMBER, parser.next());
            assertEquals(1, parser.getInt());
        }
    }

    @Test
    void aLeadingZeroWithoutContextIsRejected() {
        var in = new ByteArrayInputStream(new byte[] {0});
        assertThrows(JsonException.class, () -> PROVIDER.createParser(in));
    }
}
