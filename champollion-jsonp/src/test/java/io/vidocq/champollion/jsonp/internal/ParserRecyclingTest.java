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

import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParsingException;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A parser that {@code Jsonb} keeps between two reads is recycled with {@code reset(String)}, and remembers the keys
 * it has read: the members of a record or a bean come back with the same names in every body, so their strings are
 * built once instead of once per read (BENCH-20261029-01 follow-up).
 */
class ParserRecyclingTest {

    /** The keys of {@code json}'s top-level object, in order. */
    private static List<String> keys(ChampollionJsonParser p) {
        List<String> keys = new ArrayList<>();
        while (p.hasNext()) {
            if (p.next() == Event.KEY_NAME) keys.add(p.getString());
        }
        return keys;
    }

    @Test
    void aParserThatKeepsKeysReturnsTheSameStringForTheSameKeyAcrossReads() {
        var p = new ChampollionJsonParser("{\"title\":\"a\",\"done\":true}").keepingKeys();
        List<String> first = keys(p);
        p.reset("{\"title\":\"b\",\"done\":false}");
        List<String> second = keys(p);
        assertEquals(List.of("title", "done"), second);
        assertSame(first.get(0), second.get(0));
        assertSame(first.get(1), second.get(1));
    }

    @Test
    void aParserThatDoesNotKeepKeysBuildsThemEachTime() {
        var p = new ChampollionJsonParser("{\"title\":\"a\"}");
        String first = keys(p).get(0);
        p.reset("{\"title\":\"b\"}");
        assertNotSame(first, keys(p).get(0));
    }

    @Test
    void manyDistinctKeysAreAllReadRight() {
        var json = new StringBuilder("{");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            String key = "k" + i + (i % 3 == 0 ? "" : "_" + Integer.toHexString(i * 31));
            expected.add(key);
            json.append(i == 0 ? "" : ",").append('"').append(key).append("\":").append(i);
        }
        json.append('}');
        var p = new ChampollionJsonParser(json.toString()).keepingKeys();
        for (int round = 0; round < 3; round++) {
            assertEquals(expected, keys(p));
            p.reset(json.toString());
        }
    }

    @Test
    void keysWithEscapesSameHashesAndLongNamesAreReadRight() {
        // "Aa" and "BB" have the same hash code; escapes and long keys bypass the kept keys.
        String longKey = "x".repeat(200);
        String json = "{\"Aa\":1,\"BB\":2,\"A\\u0061\":3,\"\\\"q\\\"\":4,\"" + longKey + "\":5,\"\":6}";
        var p = new ChampollionJsonParser(json).keepingKeys();
        for (int round = 0; round < 3; round++) {
            assertEquals(List.of("Aa", "BB", "Aa", "\"q\"", longKey, ""), keys(p));
            p.reset(json);
        }
    }

    @Test
    void aKeptKeyDoesNotLeakIntoAValueOfTheSameText() {
        var p = new ChampollionJsonParser("{\"title\":\"title\"}").keepingKeys();
        assertEquals(Event.START_OBJECT, p.next());
        assertEquals(Event.KEY_NAME, p.next());
        assertEquals("title", p.getString());
        assertEquals(Event.VALUE_STRING, p.next());
        assertEquals("title", p.getString());
        assertEquals(Event.END_OBJECT, p.next());
    }

    @Test
    void aRecycledStringParserStartsOverItsPositionsAndState() {
        var p = new ChampollionJsonParser("{\n\"a\": [1, 2, {\"b\": null}]\n}").keepingKeys();
        while (p.hasNext()) p.next();
        p.reset("[true]");
        assertEquals(Event.START_ARRAY, p.next());
        assertEquals(1, p.getLocation().getLineNumber());
        assertEquals(Event.VALUE_TRUE, p.next());
        assertEquals(Event.END_ARRAY, p.next());
        p.reset("{\"a\" 1}");
        assertEquals(Event.START_OBJECT, p.next());
        assertEquals(Event.KEY_NAME, p.next());
        var error = assertThrows(JsonParsingException.class, p::next);
        assertEquals(1, error.getLocation().getLineNumber());
    }

    @Test
    void aParserMovesBetweenReaderAndStringSources() {
        var p = new ChampollionJsonParser("{\"a\":1}").keepingKeys();
        assertEquals(List.of("a"), keys(p));
        p.reset(new StringReader("{\"a\":2,\"b\":3}"));
        assertEquals(List.of("a", "b"), keys(p));
        p.reset("{\"b\":4}");
        assertEquals(List.of("b"), keys(p));
    }
}
