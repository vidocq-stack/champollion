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
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Object model — typed builder members, accessors, numbers, writer and patch edge cases")
class ValueModelEdgeCasesTest {

    private static final String MISSING = "missing";
    private static final String PATH = "path";
    private static final String VALUE = "value";

    private static JsonStructure parse(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.read();
        }
    }

    @Test
    void object_builder_typed_members() {
        JsonObject o = new ChampollionJsonObjectBuilder()
                .add("bi", BigInteger.TWO)
                .add("bd", new BigDecimal("0.25"))
                .add("l", 5_000_000_000L)
                .add("d", 1.5d)
                .add("f", false)
                .add("gone", 1)
                .remove("gone")
                .build();
        assertEquals(parse("{\"bi\":2,\"bd\":0.25,\"l\":5000000000,\"d\":1.5,\"f\":false}"), o);
        var b = new ChampollionJsonObjectBuilder();
        assertThrows(NumberFormatException.class, () -> b.add("x", Double.NaN));
        assertThrows(NumberFormatException.class, () -> b.add("x", Double.NEGATIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> b.remove(null));
    }

    @Test
    void array_builder_typed_elements() {
        var other = new ChampollionJsonArrayBuilder().add(9);
        var builder = new ChampollionJsonArrayBuilder();
        builder.addAll(List.of(JsonValue.TRUE));
        JsonArray a = builder
                .add(new BigDecimal("0.5"))
                .add(BigInteger.ONE)
                .add(2.5d)
                .add(0)
                .remove(4)
                .addAll(other)
                .build();
        assertEquals(parse("[true,0.5,1,2.5,9]"), a);
        var b = new ChampollionJsonArrayBuilder().add(1);
        assertThrows(NumberFormatException.class, () -> b.add(Double.POSITIVE_INFINITY));
        assertThrows(NumberFormatException.class, () -> b.add(0, Double.NaN));
        assertThrows(NumberFormatException.class, () -> b.set(0, Double.NaN));
        assertThrows(NumberFormatException.class, () -> b.add(Double.NaN));
    }

    @Test
    void pre_quoted_strings_are_escaped() {
        assertEquals("\"a\\\\b\\b\\f\\n\\r\\t\\u0001\\\"\"", JsonStringEscaper.preQuoted("a\\b\b\f\n\r\t\u0001\""));
        assertEquals("\"plain\"", JsonStringEscaper.preQuoted("plain"));
    }

    @Test
    void object_accessors() {
        JsonObject o = (JsonObject) parse("{\"t\":true,\"f\":false,\"n\":null,\"s\":\"x\"}");
        assertTrue(o.getBoolean("t"));
        assertFalse(o.getBoolean("f"));
        assertTrue(o.getBoolean(MISSING, true));
        assertFalse(o.getBoolean("s", false));
        assertThrows(ClassCastException.class, () -> o.getBoolean("s"));
        assertThrows(NullPointerException.class, () -> o.getBoolean(MISSING));
        assertTrue(o.isNull("n"));
        assertFalse(o.isNull("s"));
        assertThrows(NullPointerException.class, () -> o.isNull(MISSING));
    }

    @Test
    void object_factory_rejects_null_entries() {
        assertThrows(IllegalArgumentException.class, () -> ChampollionJsonObject.of(null));
        Map<String, JsonValue> nullKey = new HashMap<>();
        nullKey.put(null, JsonValue.NULL);
        assertThrows(IllegalArgumentException.class, () -> ChampollionJsonObject.of(nullKey));
        Map<String, JsonValue> nullValue = Collections.singletonMap("k", null);
        assertThrows(IllegalArgumentException.class, () -> ChampollionJsonObject.of(nullValue));
    }

    @Test
    void array_accessors() {
        JsonArray a = (JsonArray) parse("[true,false,null,\"s\"]");
        assertTrue(a.getBoolean(0));
        assertFalse(a.getBoolean(1));
        assertTrue(a.isNull(2));
        assertFalse(a.isNull(3));
        assertTrue(a.getBoolean(3, true));
        assertThrows(ClassCastException.class, () -> a.getBoolean(3));
        List<JsonString> strings = ((JsonArray) parse("[\"a\",\"b\"]")).getValuesAs(JsonString.class);
        assertEquals("b", strings.get(1).getString());
        assertThrows(IllegalArgumentException.class, () -> ChampollionJsonArray.of(null));
    }

    @Test
    void number_conversions() {
        JsonNumber n = ChampollionJsonNumber.of("1e2");
        assertFalse(n.isIntegral());
        assertFalse(ChampollionJsonNumber.of("1E2").isIntegral());
        assertEquals(100, n.intValueExact());
        assertEquals(100L, n.longValueExact());
        assertEquals(BigInteger.valueOf(100), n.bigIntegerValueExact());
        assertEquals(new BigDecimal("1e2"), n.numberValue());
        JsonNumber fraction = ChampollionJsonNumber.of("1.5");
        assertThrows(ArithmeticException.class, fraction::intValueExact);
        assertThrows(ArithmeticException.class, fraction::longValueExact);
        assertThrows(ArithmeticException.class, fraction::bigIntegerValueExact);
        assertThrows(NullPointerException.class, () -> ChampollionJsonNumber.of((String) null));
        assertThrows(NullPointerException.class, () -> ChampollionJsonNumber.of((BigDecimal) null));
    }

    @Test
    void numbers_compare_by_value() {
        JsonNumber n = ChampollionJsonNumber.of("1.50");
        assertEquals(n, n);
        assertEquals(ChampollionJsonNumber.of("1.5"), n);
        assertEquals(ChampollionJsonNumber.of("1.5").hashCode(), n.hashCode());
        assertNotEquals(n, (Object) "1.5");
        assertNotEquals(ChampollionJsonNumber.of(2), n);
    }

    @Test
    void writer_emits_every_member_kind() {
        StringWriter out = new StringWriter();
        JsonWriter w = new ChampollionJsonWriter(out, false);
        w.write(parse("{\"o\":{\"x\":false},\"a\":[false,null],\"f\":false,\"n\":null}"));
        assertEquals("{\"o\":{\"x\":false},\"a\":[false,null],\"f\":false,\"n\":null}", out.toString());
        JsonValue t = JsonValue.TRUE;
        assertThrows(IllegalStateException.class, () -> w.write(t));
        StringWriter scalar = new StringWriter();
        try (JsonWriter w2 = new ChampollionJsonWriter(scalar, false)) {
            w2.write(JsonValue.FALSE);
        }
        assertEquals("false", scalar.toString());
    }

    @Test
    void test_operation_compares_by_value() {
        JsonStructure doc = parse("{\"o\":{\"a\":[1,{\"b\":null}],\"t\":true},\"s\":\"x\"}");
        JsonPatch same = Json.createPatchBuilder()
                .test("/o", parse("{\"t\":true,\"a\":[1.0,{\"b\":null}]}"))
                .build();
        assertEquals(doc, same.apply(doc));
        for (String differing : List.of("{\"t\":true}", "{\"t\":true,\"a\":[1,{\"b\":null}],\"c\":1}",
                "{\"t\":true,\"x\":[1,{\"b\":null}]}", "{\"t\":true,\"a\":[1]}", "{\"t\":true,\"a\":[2,{\"b\":null}]}",
                "{\"t\":false,\"a\":[1,{\"b\":null}]}")) {
            JsonPatch p = Json.createPatchBuilder().test("/o", parse(differing)).build();
            assertThrows(JsonException.class, () -> p.apply(doc), differing);
        }
    }

    @Test
    void malformed_operations_are_rejected() {
        JsonStructure doc = parse("{}");
        JsonPatch notObject = new ChampollionJsonPatch((JsonArray) parse("[1]"));
        assertThrows(JsonException.class, () -> notObject.apply(doc));
        JsonPatch pathNotString = new ChampollionJsonPatch(Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("op", "add").add(PATH, 1).add(VALUE, 1)).build());
        assertThrows(JsonException.class, () -> pathNotString.apply(doc));
    }
}
