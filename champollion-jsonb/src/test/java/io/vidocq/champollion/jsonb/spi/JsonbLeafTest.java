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
package io.vidocq.champollion.jsonb.spi;

import jakarta.json.Json;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A generated binding maps the native types through {@link JsonbLeaf}: same text as the runtime. */
class JsonbLeafTest {

    private static String write(JsonbLeaf leaf, Object value) {
        var out = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(out)) {
            g.writeStartArray();
            leaf.write(g, value);
            g.writeEnd();
        }
        return out.toString();
    }

    private static Object read(JsonbLeaf leaf, String json) {
        try (JsonParser p = Json.createParser(new StringReader(json))) {
            return leaf.read(p);
        }
    }

    @Test
    void writesAsTheRuntimeDoes() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            for (Object value : new Object[] {LocalDate.of(2026, 10, 31), Instant.parse("2026-10-31T10:15:30Z"),
                    new BigDecimal("12.50"), UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), 42, 'x'}) {
                assertEquals(jsonb.toJson(new Object[] {value}), write(JsonbLeaf.of(value.getClass()), value),
                        value.getClass().getName());
            }
        }
    }

    @Test
    void readsWhatItWrites() {
        JsonbLeaf leaf = JsonbLeaf.of(LocalDate.class);
        assertEquals(LocalDate.of(2026, 10, 31), read(leaf, "\"2026-10-31\""));
        assertNull(read(leaf, "null"));
        assertEquals(7L, read(JsonbLeaf.of(long.class), "7"));
    }

    @Test
    void aBadValueIsAJsonbException() {
        JsonbLeaf leaf = JsonbLeaf.of(LocalDate.class);
        assertThrows(JsonbException.class, () -> read(leaf, "\"31/10/2026\""));
    }

    @Test
    void aTypeJsonbDoesNotMapNativelyHasNone() {
        assertThrows(IllegalArgumentException.class, () -> JsonbLeaf.of(Object.class));
        assertThrows(IllegalArgumentException.class, () -> JsonbLeaf.of(JsonbLeafTest.class));
    }
}
