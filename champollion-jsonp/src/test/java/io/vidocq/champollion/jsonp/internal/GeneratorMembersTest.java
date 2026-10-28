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
import jakarta.json.stream.JsonGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChampollionJsonGenerator — named members, JsonValue members and raw keys")
class GeneratorMembersTest {

    private static final String KEY_A = "\"a\"";
    private static final String KEY_B_COLON = "\"b\":";

    private static JsonValue parse(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.readValue();
        }
    }

    @Test
    void json_value_members_are_written_recursively() {
        JsonValue nested = parse("{\"o\":{\"x\":[1,\"s\",true,false,null,{}]},\"e\":[]}");
        StringWriter w = new StringWriter();
        var g = new ChampollionJsonGenerator(w, false);
        g.writeStartObject()
                .write("v", nested)
                .write("big", new BigInteger("123456789012345678901234567890"))
                .write("n", (JsonValue) null)
                .writeEnd()
                .close();
        assertEquals("{\"v\":{\"o\":{\"x\":[1,\"s\",true,false,null,{}]},\"e\":[]},"
                + "\"big\":123456789012345678901234567890,\"n\":null}", w.toString());
    }

    @Test
    void raw_keys_compact() {
        StringWriter w = new StringWriter();
        var g = new ChampollionJsonGenerator(w, false);
        g.writeStartObject();
        g.writeKeyRaw(KEY_A);
        g.write(1);
        g.writeKeyRawWithColon(KEY_B_COLON);
        g.write(2);
        g.writeKeyRaw("\"c\"");
        g.write("x");
        g.writeEnd();
        g.close();
        assertEquals("{\"a\":1,\"b\":2,\"c\":\"x\"}", w.toString());
    }

    @Test
    void raw_keys_pretty() {
        StringWriter w = new StringWriter();
        var g = new ChampollionJsonGenerator(w, true);
        g.writeStartObject();
        g.writeKeyRawWithColon(KEY_B_COLON);
        g.write(2);
        g.writeKeyRaw(KEY_A);
        g.write(1);
        g.writeKeyRawWithColon("\"z\":");
        g.write(true);
        g.writeEnd();
        g.close();
        String out = w.toString();
        assertTrue(out.contains("\"b\": 2"), out);
        assertTrue(out.contains("\"a\": 1"), out);
        assertTrue(out.contains("\n"), out);
        assertEquals(parse("{\"b\":2,\"a\":1,\"z\":true}"), parse(out));
    }

    @Test
    void raw_keys_outside_an_object_are_rejected() {
        var g = new ChampollionJsonGenerator(new StringWriter(), false);
        g.writeStartArray();
        assertThrows(JsonGenerationException.class, () -> g.writeKeyRaw(KEY_A));
        assertThrows(JsonGenerationException.class, () -> g.writeKeyRawWithColon(KEY_B_COLON));
    }

    @Test
    void writeEnd_errors() {
        var root = new ChampollionJsonGenerator(new StringWriter(), false);
        assertThrows(JsonGenerationException.class, root::writeEnd);
        var dangling = new ChampollionJsonGenerator(new StringWriter(), false);
        dangling.writeStartObject().writeKey("k");
        assertThrows(JsonGenerationException.class, dangling::writeEnd);
        var finished = new ChampollionJsonGenerator(new StringWriter(), false);
        finished.writeStartArray().writeEnd();
        assertThrows(JsonGenerationException.class, finished::writeEnd);
    }

    @Test
    void a_null_writer_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChampollionJsonGenerator((java.io.Writer) null, false));
    }
}
