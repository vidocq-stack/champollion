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
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsonValueParser — events over an in-memory structure (spec §3.4)")
class JsonValueParserTest {

    private static final String DOC =
            "{\"s\":\"text\",\"i\":42,\"d\":1.5,\"t\":true,\"f\":false,\"n\":null,"
                    + "\"o\":{\"k\":\"v\"},\"a\":[1,[2],{}]}";

    private static final JsonParserFactory FACTORY = Json.createParserFactory(null);

    private static JsonObject object(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.readObject();
        }
    }

    private static JsonArray array(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.readArray();
        }
    }

    /** The events a streaming parser emits for the same text, the reference. */
    private static List<Event> streamingEvents(String json) {
        List<Event> out = new ArrayList<>();
        try (JsonParser p = Json.createParser(new StringReader(json))) {
            while (p.hasNext()) out.add(p.next());
        }
        return out;
    }

    private static List<Event> drain(JsonParser p) {
        List<Event> out = new ArrayList<>();
        while (p.hasNext()) out.add(p.next());
        return out;
    }

    @Test
    void object_emits_the_same_events_as_the_streaming_parser() {
        try (JsonParser p = FACTORY.createParser(object(DOC))) {
            assertEquals(streamingEvents(DOC), drain(p));
            assertFalse(p.hasNext());
        }
    }

    @Test
    void array_emits_the_same_events_as_the_streaming_parser() {
        String json = "[\"x\",-3,{\"a\":[]},[null,true]]";
        try (JsonParser p = FACTORY.createParser(array(json))) {
            assertEquals(streamingEvents(json), drain(p));
        }
    }

    @Test
    void scalar_accessors_follow_the_current_event() {
        try (JsonParser p = FACTORY.createParser(object(DOC))) {
            assertEquals(Event.START_OBJECT, p.next());
            assertEquals(Event.KEY_NAME, p.next());
            assertEquals("s", p.getString());
            assertEquals(Event.VALUE_STRING, p.next());
            assertEquals("text", p.getString());
            assertEquals(Event.VALUE_STRING, p.currentEvent());
            p.next();
            assertEquals(Event.VALUE_NUMBER, p.next());
            assertTrue(p.isIntegralNumber());
            assertEquals(42, p.getInt());
            assertEquals(42L, p.getLong());
            assertEquals(new BigDecimal(42), p.getBigDecimal());
            assertEquals("42", p.getString());
            p.next();
            assertEquals(Event.VALUE_NUMBER, p.next());
            assertFalse(p.isIntegralNumber());
            assertEquals(new BigDecimal("1.5"), p.getBigDecimal());
        }
    }

    @Test
    void scalar_values_are_returned_by_getValue() {
        try (JsonParser p = FACTORY.createParser(object(DOC))) {
            p.next();
            p.next();
            assertEquals(Json.createValue("s"), p.getValue());
            p.next();
            assertEquals(Json.createValue("text"), p.getValue());
            p.next();
            p.next();
            assertEquals(Json.createValue(42), p.getValue());
            p.next();
            p.next();
            p.next();
            p.next();
            assertSame(JsonValue.TRUE, p.getValue());
            p.next();
            p.next();
            assertSame(JsonValue.FALSE, p.getValue());
            p.next();
            p.next();
            assertSame(JsonValue.NULL, p.getValue());
        }
    }

    @Test
    void getValue_on_a_nested_start_returns_the_whole_structure_and_resumes_after_it() {
        try (JsonParser p = FACTORY.createParser(object(DOC))) {
            Event e;
            while ((e = p.next()) != Event.KEY_NAME || !"o".equals(p.getString())) {
                assertTrue(p.hasNext(), "key o not reached, last event " + e);
            }
            assertEquals(Event.START_OBJECT, p.next());
            assertEquals(object("{\"k\":\"v\"}"), p.getValue());
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertEquals(Event.KEY_NAME, p.next());
            assertEquals("a", p.getString());
            assertEquals(Event.START_ARRAY, p.next());
            assertEquals(array("[1,[2],{}]"), p.getValue());
            assertEquals(Event.END_ARRAY, p.currentEvent());
            assertEquals(Event.END_OBJECT, p.next());
            assertFalse(p.hasNext());
        }
    }

    @Test
    void getValue_before_any_event_reads_the_root() {
        JsonObject root = object(DOC);
        try (JsonParser p = FACTORY.createParser(root)) {
            assertEquals(root, p.getValue());
            assertFalse(p.hasNext());
        }
    }

    @Test
    void getObject_and_getArray_return_the_current_structure() {
        JsonObject root = object(DOC);
        try (JsonParser p = FACTORY.createParser(root)) {
            p.next();
            assertEquals(root, p.getObject());
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertFalse(p.hasNext());
        }
        JsonArray arr = array("[1,2]");
        try (JsonParser p = FACTORY.createParser(arr)) {
            p.next();
            assertEquals(arr, p.getArray());
            assertEquals(Event.END_ARRAY, p.currentEvent());
        }
    }

    @Test
    void streams_expose_the_current_structure() {
        try (JsonParser p = FACTORY.createParser(object("{\"a\":1,\"b\":2}"))) {
            p.next();
            Map<String, JsonValue> entries = p.getObjectStream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(Map.of("a", Json.createValue(1), "b", Json.createValue(2)), entries);
        }
        try (JsonParser p = FACTORY.createParser(array("[\"x\",\"y\"]"))) {
            p.next();
            assertEquals(List.of(Json.createValue("x"), Json.createValue("y")), p.getArrayStream().toList());
        }
        try (JsonParser p = FACTORY.createParser(array("[7]"))) {
            p.next();
            p.next();
            assertEquals(List.of(Json.createValue(7)), p.getValueStream().toList());
        }
    }

    @Test
    void skip_leaves_the_current_structure() {
        try (JsonParser p = FACTORY.createParser(object("{\"a\":[1,2],\"b\":{\"c\":1},\"d\":3}"))) {
            p.next();
            p.next();
            p.next();
            p.skipArray();
            assertEquals(Event.END_ARRAY, p.currentEvent());
            p.next();
            p.next();
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertEquals(Event.KEY_NAME, p.next());
            assertEquals("d", p.getString());
            p.skipArray();
            p.skipObject();
            assertEquals(Event.KEY_NAME, p.currentEvent());
        }
    }

    @Test
    void a_scalar_root_emits_one_event() {
        try (JsonParser p = new JsonValueParser(Json.createValue("only"))) {
            assertEquals(List.of(Event.VALUE_STRING), drain(p));
            assertEquals("only", p.getString());
        }
        try (JsonParser p = new JsonValueParser(JsonValue.NULL)) {
            assertEquals(List.of(Event.VALUE_NULL), drain(p));
        }
    }

    @Test
    void location_is_unknown() {
        try (JsonParser p = FACTORY.createParser(array("[]"))) {
            var loc = p.getLocation();
            assertEquals(0L, loc.getLineNumber());
            assertEquals(0L, loc.getColumnNumber());
            assertEquals(0L, loc.getStreamOffset());
        }
    }

    @Test
    void close_ends_the_iteration() {
        JsonParser p = FACTORY.createParser(array("[1,2,3]"));
        p.next();
        p.close();
        assertFalse(p.hasNext());
    }

    @Test
    void a_null_root_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new JsonValueParser(null));
    }

    @Test
    void next_past_the_end_throws() {
        try (JsonParser p = FACTORY.createParser(array("[]"))) {
            drain(p);
            assertThrows(NoSuchElementException.class, p::next);
        }
    }

    @Test
    void accessors_reject_the_wrong_event() {
        try (JsonParser p = FACTORY.createParser(object("{\"a\":true}"))) {
            p.next();
            assertThrows(IllegalStateException.class, p::getString);
            assertThrows(IllegalStateException.class, p::getInt);
            assertThrows(IllegalStateException.class, p::getLong);
            assertThrows(IllegalStateException.class, p::isIntegralNumber);
            assertThrows(IllegalStateException.class, p::getBigDecimal);
            assertThrows(IllegalStateException.class, p::getArray);
            p.next();
            assertThrows(IllegalStateException.class, p::getObject);
            p.next();
            p.next();
            assertThrows(IllegalStateException.class, p::getValue);
        }
    }
}
