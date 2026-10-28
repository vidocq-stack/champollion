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
import jakarta.json.JsonObject;
import jakarta.json.JsonPointer;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChampollionJsonPointer — RFC 6901 edge cases and nested mutations")
class PointerEdgeCasesTest {

    private static final String NESTED_B = "/a/1/b";
    private static final JsonObject DOC = (JsonObject) parse("{\"a\":[10,{\"b\":\"x\"}],\"s\":\"str\",\"o\":{}}");

    private static JsonStructure parse(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            return r.read();
        }
    }

    private static JsonPointer pointer(String p) {
        return new ChampollionJsonPointer(p);
    }

    @Test
    void contains_value_follows_objects_and_arrays() {
        assertTrue(pointer(NESTED_B).containsValue(DOC));
        assertTrue(pointer("").containsValue(DOC));
        assertFalse(pointer("/a/2").containsValue(DOC));
        assertFalse(pointer("/a/x").containsValue(DOC));
        assertFalse(pointer("/a/-").containsValue(DOC));
        assertFalse(pointer("/s/0").containsValue(DOC));
        assertFalse(pointer("/missing/0").containsValue(DOC));
        assertFalse(pointer("/~2").containsValue(DOC));
    }

    @Test
    void lookup_errors() {
        assertFails("/s/x", ptr -> ptr.getValue(DOC));
        assertFails("/a/-", ptr -> ptr.getValue(DOC));
        assertFails("/a/", ptr -> ptr.getValue(DOC));
        assertFails("/a/9", ptr -> ptr.getValue(DOC));
        assertFails("/a/01", ptr -> ptr.getValue(DOC));
        assertFails("/nope", ptr -> ptr.getValue(DOC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/~", "/a~", "/~2", "/a~x"})
    void malformed_escapes_fail_at_resolution(String p) {
        JsonPointer ptr = pointer(p);
        assertThrows(JsonException.class, () -> ptr.getValue(DOC));
        JsonValue v = Json.createValue(1);
        assertThrows(JsonException.class, () -> ptr.add(DOC, v));
    }

    @Test
    void construction_errors() {
        assertThrows(IllegalArgumentException.class, () -> new ChampollionJsonPointer(null));
        assertThrows(JsonException.class, () -> new ChampollionJsonPointer("a"));
    }

    @Test
    void escapes_and_empty_tokens_name_members() {
        JsonObject o = (JsonObject) parse("{\"\":{\"a/b\":{\"m~n\":1}}}");
        assertEquals(Json.createValue(1), pointer("//a~1b/m~0n").getValue(o));
    }

    @Test
    void root_mutations() {
        JsonStructure replacement = parse("[1]");
        assertSame(replacement, pointer("").add(DOC, replacement));
        assertSame(replacement, pointer("").replace(DOC, replacement));
        JsonPointer root = pointer("");
        assertThrows(JsonException.class, () -> root.remove(DOC));
        JsonValue scalar = Json.createValue(1);
        assertThrows(JsonException.class, () -> root.add(DOC, scalar));
    }

    @Test
    void nested_mutations_copy_the_path() {
        JsonValue y = Json.createValue("y");
        assertEquals(parse("{\"a\":[10,{\"b\":\"y\"}],\"s\":\"str\",\"o\":{}}"), pointer(NESTED_B).replace(DOC, y));
        assertEquals(parse("{\"a\":[10,{}],\"s\":\"str\",\"o\":{}}"), pointer(NESTED_B).remove(DOC));
        assertEquals(parse("{\"a\":[10,{\"b\":\"x\"},\"y\"],\"s\":\"str\",\"o\":{}}"), pointer("/a/-").add(DOC, y));
        assertEquals(parse("{\"a\":[\"y\",{\"b\":\"x\"}],\"s\":\"str\",\"o\":{}}"), pointer("/a/0").replace(DOC, y));
        assertEquals(parse("{\"a\":[{\"b\":\"x\"}],\"s\":\"str\",\"o\":{}}"), pointer("/a/0").remove(DOC));
        assertEquals(parse("{\"a\":[10,{\"b\":\"x\"}],\"s\":\"str\",\"o\":{\"k\":\"y\"}}"), pointer("/o/k").add(DOC, y));
        JsonArray arr = (JsonArray) parse("[[1]]");
        assertEquals(parse("[[1,\"y\"]]"), pointer("/0/1").add(arr, y));
    }

    @Test
    void mutation_errors() {
        JsonValue y = Json.createValue("y");
        assertFails("/o/k", ptr -> ptr.replace(DOC, y));
        assertFails("/o/k", ptr -> ptr.remove(DOC));
        assertFails("/a/2", ptr -> ptr.replace(DOC, y));
        assertFails("/a/2", ptr -> ptr.remove(DOC));
        assertFails("/a/5/b", ptr -> ptr.add(DOC, y));
        assertFails("/a/2/b", ptr -> ptr.add(DOC, y));
        assertFails("/missing/b", ptr -> ptr.add(DOC, y));
        assertFails("/s/b", ptr -> ptr.add(DOC, y));
    }

    /** Asserts that {@code action} on the pointer {@code p} throws a {@link JsonException}. */
    private static void assertFails(String p, java.util.function.Consumer<JsonPointer> action) {
        JsonPointer ptr = pointer(p);
        assertThrows(JsonException.class, () -> action.accept(ptr), p);
    }

    @Test
    void identity_is_the_raw_text() {
        assertEquals(pointer("/a/0"), pointer("/a/0"));
        assertEquals(pointer("/a/0").hashCode(), pointer("/a/0").hashCode());
        assertNotEquals(pointer("/a/0"), pointer("/a/1"));
        assertEquals("/a/~1", pointer("/a/~1").toString());
    }
}
