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

import jakarta.json.Json;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * BUG-20261028-16 — JSON-P 2.1 {@code JsonParser.skipObject()} / {@code skipArray()}: "If the parser is in object
 * (array) context, i.e. it has called {@code next()} without a matching {@code END_OBJECT} (END_ARRAY), the parser
 * is advanced to the matching event; otherwise it does nothing." This holds anywhere between the container's start
 * and its end, not only on the {@code START_*} event. Both parsers are checked.
 */
@DisplayName("skipObject / skipArray from inside a container (JSON-P 2.1 JsonParser javadoc)")
class SkipInContainerTest {

    private static final String OBJECT = "{\"a\":1,\"b\":{\"c\":2},\"d\":3}";

    private static void onBothParsers(String json, Consumer<JsonParser> check) {
        try (JsonParser streaming = new ChampollionJsonParser(json)) {
            check.accept(streaming);
        }
        JsonValue value;
        try (var reader = Json.createReader(new StringReader(json))) {
            value = reader.readValue();
        }
        try (JsonParser inMemory = new JsonValueParser(value)) {
            check.accept(inMemory);
        }
    }

    @Test
    void skipObject_on_a_key_name_advances_to_the_end_of_the_object() {
        onBothParsers(OBJECT, p -> {
            p.next();
            assertEquals(Event.KEY_NAME, p.next());
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertFalse(p.hasNext());
        });
    }

    @Test
    void skipObject_on_a_value_leaves_the_innermost_object_only() {
        onBothParsers(OBJECT, p -> {
            for (int i = 0; i < 6; i++) p.next(); // { a 1 b { c
            assertEquals(Event.VALUE_NUMBER, p.next());
            assertEquals(2, p.getInt());
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertEquals(Event.KEY_NAME, p.next());
            assertEquals("d", p.getString());
        });
    }

    @Test
    void skipObject_after_a_hasNext_look_ahead_on_a_nested_start() {
        onBothParsers("{\"a\":[1],\"b\":2}", p -> {
            p.next();
            p.next();
            assertEquals(true, p.hasNext());
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertFalse(p.hasNext());
        });
    }

    @Test
    void skipObject_after_a_hasNext_look_ahead_on_the_closing_event() {
        onBothParsers("{\"a\":1}", p -> {
            p.next();
            p.next();
            p.next();
            assertEquals(true, p.hasNext());
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertFalse(p.hasNext());
        });
    }

    @Test
    void skipArray_on_a_value_advances_to_the_end_of_the_array() {
        onBothParsers("[1,[2,3],4]", p -> {
            p.next();
            assertEquals(Event.VALUE_NUMBER, p.next());
            p.skipArray();
            assertEquals(Event.END_ARRAY, p.currentEvent());
            assertFalse(p.hasNext());
        });
    }

    @Test
    void skipArray_inside_a_nested_array_stays_in_the_outer_one() {
        onBothParsers("[1,[2,3],4]", p -> {
            p.next();
            p.next();
            p.next();
            assertEquals(Event.VALUE_NUMBER, p.next());
            p.skipArray();
            assertEquals(Event.END_ARRAY, p.currentEvent());
            assertEquals(Event.VALUE_NUMBER, p.next());
            assertEquals(4, p.getInt());
        });
    }

    @Test
    void a_skip_of_the_other_kind_does_nothing() {
        onBothParsers("[1,2]", p -> {
            p.next();
            p.next();
            p.skipObject();
            assertEquals(Event.VALUE_NUMBER, p.currentEvent());
            assertEquals(Event.VALUE_NUMBER, p.next());
            assertEquals(2, p.getInt());
        });
        onBothParsers(OBJECT, p -> {
            p.next();
            p.next();
            p.skipArray();
            assertEquals(Event.KEY_NAME, p.currentEvent());
        });
    }

    @Test
    void a_skip_outside_any_container_does_nothing() {
        onBothParsers(OBJECT, p -> {
            p.skipObject();
            p.skipArray();
            assertEquals(Event.START_OBJECT, p.next());
        });
        onBothParsers("[]", p -> {
            p.next();
            p.next();
            p.skipArray();
            assertEquals(Event.END_ARRAY, p.currentEvent());
        });
    }
}
