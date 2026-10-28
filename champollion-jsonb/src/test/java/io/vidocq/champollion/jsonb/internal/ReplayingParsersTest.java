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
package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.PrimedJsonParser;
import jakarta.json.Json;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two parsers that give back an event already consumed — {@link ReplayJsonParser} (deserialization context)
 * and {@link PrimedJsonParser} (generated bindings) — and the deserialization context that chooses between replaying
 * and passing the parser through.
 */
@DisplayName("JSON-B — replaying parsers and the deserialization context")
class ReplayingParsersTest {

    private static final String DOCUMENT = "{\"n\":12,\"d\":1.5,\"s\":\"x\",\"o\":{\"k\":1},\"a\":[1,2],\"t\":[3],\"u\":{}}";

    private static JsonParser parser(String json) {
        return Json.createParser(new StringReader(json));
    }

    /** Walks DOCUMENT through a wrapper created on its first event, exercising every delegated method. */
    private static void walk(BiFunction<JsonParser.Event, JsonParser, JsonParser> wrap) {
        try (JsonParser delegate = parser(DOCUMENT)) {
            JsonParser.Event first = delegate.next();
            JsonParser p = wrap.apply(first, delegate);
            assertTrue(p.hasNext());
            assertEquals(JsonParser.Event.START_OBJECT, p.next());
            assertEquals(JsonParser.Event.KEY_NAME, p.next());
            assertEquals("n", p.getString());
            p.next();
            assertTrue(p.isIntegralNumber());
            assertEquals(12, p.getInt());
            assertEquals(12L, p.getLong());
            assertNotNull(p.getLocation());
            p.next();
            p.next();
            assertEquals(new BigDecimal("1.5"), p.getBigDecimal());
            p.next();
            p.next();
            assertEquals(Json.createValue("x"), p.getValue());
            p.next();
            p.next();
            assertEquals(Map.of("k", Json.createValue(1)), p.getObject());
            p.next();
            p.next();
            assertEquals(List.of(Json.createValue(1), Json.createValue(2)), p.getArray());
            p.next();
            p.next();
            p.skipArray();
            p.next();
            p.next();
            p.skipObject();
            assertEquals(JsonParser.Event.END_OBJECT, p.next());
            assertFalse(p.hasNext());
            p.close();
        }
    }

    @Test
    void replay_parser_gives_back_the_consumed_event_then_delegates() {
        walk(ReplayJsonParser::new);
    }

    @Test
    void primed_parser_gives_back_the_consumed_event_then_delegates() {
        walk(PrimedJsonParser::new);
    }

    @Test
    void replay_parser_has_no_current_event_until_the_replay_is_consumed() {
        try (JsonParser delegate = parser("[1]")) {
            var p = new ReplayJsonParser(delegate.next(), delegate);
            assertNull(p.currentEvent());
            p.next();
            assertEquals(JsonParser.Event.START_ARRAY, p.currentEvent());
        }
    }

    @Test
    void streams_are_delegated() {
        try (JsonParser delegate = parser("[[1,2],{\"a\":true},3]")) {
            var replay = new ReplayJsonParser(delegate.next(), delegate);
            replay.next();
            replay.next();
            assertEquals(2, replay.getArrayStream().count());
            replay.next();
            assertEquals(1, replay.getObjectStream().count());
        }
        try (JsonParser delegate = parser("[[1,2],{\"a\":true}]")) {
            var primed = new PrimedJsonParser(delegate.next(), delegate);
            primed.next();
            primed.next();
            assertEquals(2, primed.getArrayStream().count());
            primed.next();
            assertEquals(1, primed.getObjectStream().count());
        }
        try (JsonParser delegate = parser("7")) {
            assertEquals(List.of(Json.createValue(7)), new ReplayJsonParser(null, delegate).getValueStream().toList());
        }
        try (JsonParser delegate = parser("8")) {
            assertEquals(List.of(Json.createValue(8)), new PrimedJsonParser(null, delegate).getValueStream().toList());
        }
    }

    @Test
    void the_context_replays_a_consumed_start_or_scalar_event() {
        var ctx = new ChampollionDeserializationContext(new RuntimeReadRegistry());
        try (JsonParser p = parser("{\"a\":[1,2],\"b\":\"s\",\"c\":true,\"d\":false,\"e\":null,\"f\":4}")) {
            p.next();
            Map<String, Object> map = ctx.deserialize((java.lang.reflect.Type) Map.class, p);
            var expected = new java.util.LinkedHashMap<String, Object>();
            expected.put("a", List.of(1, 2));
            expected.put("b", "s");
            expected.put("c", true);
            expected.put("d", false);
            expected.put("e", null);
            expected.put("f", 4);
            assertEquals(expected, map);
        }
        try (JsonParser p = parser("[\"s\",true,false,null,4]")) {
            p.next();
            p.next();
            assertEquals("s", ctx.deserialize(String.class, p));
            p.next();
            assertEquals(Boolean.TRUE, ctx.deserialize(Boolean.class, p));
            p.next();
            assertEquals(Boolean.FALSE, ctx.deserialize(Boolean.class, p));
            p.next();
            assertNull(ctx.deserialize(String.class, p));
            p.next();
            assertEquals(4, ctx.deserialize(Integer.class, p));
        }
    }

    @Test
    void the_context_passes_the_parser_through_before_the_first_event_and_on_a_key() {
        var ctx = new ChampollionDeserializationContext(new RuntimeReadRegistry());
        try (JsonParser p = parser("[5]")) {
            assertEquals(List.of(5), ctx.deserialize((java.lang.reflect.Type) List.class, p));
        }
        try (JsonParser p = parser("{\"k\":6}")) {
            p.next();
            p.next();
            assertEquals(JsonParser.Event.KEY_NAME, p.currentEvent());
            assertEquals(6, ctx.deserialize(Integer.class, p));
        }
    }

    @Test
    void the_serialization_context_writes_values_keys_and_nulls() {
        var ctx = new ChampollionSerializationContext(new RuntimeBindingRegistry());
        var out = new java.io.StringWriter();
        try (var g = Json.createGenerator(out)) {
            g.writeStartObject();
            ctx.serialize("a", 1, g);
            ctx.serialize("b", null, g);
            g.writeKey("c");
            ctx.serialize(List.of("x"), g);
            g.writeKey("d");
            ctx.serialize(null, g);
            g.writeEnd();
        }
        assertEquals("{\"a\":1,\"b\":null,\"c\":[\"x\"],\"d\":null}", out.toString());
    }
}
