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
import jakarta.json.stream.JsonParsingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ChampollionJsonParser — value, structure and stream accessors (spec §3.10)")
class ParserValueAccessTest {

    private static final String SCALARS = "[\"s\",12.5,true,false,null]";
    private static final String NESTED = "{\"a\":{\"b\":[1,{\"c\":[]}]},\"d\":2}";

    private static JsonValue value(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.readValue();
        }
    }

    private static JsonParser parser(String json) {
        return new ChampollionJsonParser(json);
    }

    @Test
    void getValue_returns_each_scalar_kind() {
        try (JsonParser p = parser(SCALARS)) {
            p.next();
            p.next();
            assertEquals(Json.createValue("s"), p.getValue());
            p.next();
            assertEquals(Json.createValue(new java.math.BigDecimal("12.5")), p.getValue());
            p.next();
            assertSame(JsonValue.TRUE, p.getValue());
            p.next();
            assertSame(JsonValue.FALSE, p.getValue());
            p.next();
            assertSame(JsonValue.NULL, p.getValue());
            p.next();
            assertThrows(IllegalStateException.class, p::getValue);
        }
    }

    @Test
    void getValue_on_a_key_is_the_key_string() {
        try (JsonParser p = parser(NESTED)) {
            p.next();
            p.next();
            assertEquals(Json.createValue("a"), p.getValue());
        }
    }

    @Test
    void getValue_of_a_fresh_parser_reads_the_root() {
        try (JsonParser p = parser(NESTED)) {
            assertEquals(value(NESTED), p.getValue());
        }
        try (JsonParser p = parser("  ")) {
            assertThrows(JsonParsingException.class, p::getValue);
        }
    }

    @Test
    void getObject_and_getArray_accept_a_fresh_parser() {
        try (JsonParser p = parser(NESTED)) {
            assertEquals(value(NESTED), p.getObject());
        }
        try (JsonParser p = parser(SCALARS)) {
            assertEquals(value(SCALARS), p.getArray());
        }
    }

    @Test
    void getObject_and_getArray_reject_the_wrong_root_or_event() {
        try (JsonParser p = parser(SCALARS)) {
            assertThrows(IllegalStateException.class, p::getObject);
        }
        try (JsonParser p = parser(NESTED)) {
            assertThrows(IllegalStateException.class, p::getArray);
        }
        try (JsonParser p = parser(SCALARS)) {
            p.next();
            p.next();
            assertThrows(IllegalStateException.class, p::getObject);
            assertThrows(IllegalStateException.class, p::getArray);
            assertThrows(IllegalStateException.class, p::getArrayStream);
            assertThrows(IllegalStateException.class, p::getObjectStream);
        }
    }

    @Test
    void nested_structures_are_read_in_place() {
        try (JsonParser p = parser(NESTED)) {
            p.next();
            p.next();
            assertEquals(Event.START_OBJECT, p.next());
            assertEquals(value("{\"b\":[1,{\"c\":[]}]}"), p.getObject());
            assertEquals(Event.KEY_NAME, p.next());
            assertEquals("d", p.getString());
        }
    }

    @Test
    void streams_over_the_current_structure() {
        try (JsonParser p = parser(SCALARS)) {
            p.next();
            assertEquals(5, p.getArrayStream().toList().size());
            assertFalse(p.hasNext());
        }
        try (JsonParser p = parser("{\"x\":1,\"y\":[2]}")) {
            p.next();
            Map<String, JsonValue> m = p.getObjectStream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(Map.of("x", value("1"), "y", value("[2]")), m);
        }
    }

    @Test
    void getValueStream_only_at_the_root() {
        try (JsonParser p = parser("[1]")) {
            assertEquals(List.of(value("[1]")), p.getValueStream().toList());
        }
        try (JsonParser p = parser("[1]")) {
            p.next();
            assertThrows(IllegalStateException.class, p::getValueStream);
        }
    }

    @Test
    void skip_jumps_over_nested_containers_of_the_same_kind() {
        try (JsonParser p = parser("[[1,[2]],{\"k\":{\"j\":{}}},3]")) {
            p.next();
            assertEquals(Event.START_ARRAY, p.next());
            p.skipArray();
            assertEquals(Event.END_ARRAY, p.currentEvent());
            assertEquals(Event.START_OBJECT, p.next());
            p.skipObject();
            assertEquals(Event.END_OBJECT, p.currentEvent());
            assertEquals(Event.VALUE_NUMBER, p.next());
            p.skipObject();
            p.skipArray();
            assertEquals(3, p.getInt());
        }
    }

    @Test
    void reset_reuses_the_parser_for_a_new_document() {
        ChampollionJsonParser p = new ChampollionJsonParser("[1]");
        p.next();
        p.reset("{\"a\":true}");
        assertEquals(value("{\"a\":true}"), p.getObject());
        p.reset(new StringReader("\"x\""));
        assertEquals(Event.VALUE_STRING, p.next());
        assertEquals("x", p.getString());
        assertFalse(p.hasNext());
        p.close();
    }
}
