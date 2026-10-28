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

import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("JsonArray / JsonObject equality — JSON-P §4.1, §4.2 (List and Map contracts)")
class StructureEqualityTest {

    private static final JsonValue ONE = ChampollionJsonNumber.of(1);
    private static final JsonValue TWO = ChampollionJsonNumber.of(2);

    @Test
    void arrays_with_the_same_values_in_the_same_order_are_equal() {
        var a = ChampollionJsonArray.of(List.of(ONE, TWO));
        var b = ChampollionJsonArray.of(List.of(ONE, TWO));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ChampollionJsonArray.of(List.of(TWO, ONE)));
    }

    @Test
    void an_array_equals_any_list_with_the_same_values() {
        var a = ChampollionJsonArray.of(List.of(ONE, TWO));
        List<JsonValue> plain = new ArrayList<>(List.of(ONE, TWO));
        assertEquals(plain, a);
        assertEquals(a, plain);
        assertEquals(plain.hashCode(), a.hashCode());
        assertNotEquals(a, List.of(ONE));
    }

    @Test
    void objects_with_the_same_members_are_equal_whatever_their_order() {
        var a = ChampollionJsonObject.of(orderedMembers("a", ONE, "b", TWO));
        var b = ChampollionJsonObject.of(orderedMembers("b", TWO, "a", ONE));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ChampollionJsonObject.of(Map.of("a", ONE)));
    }

    @Test
    void an_object_equals_any_map_with_the_same_members() {
        var a = ChampollionJsonObject.of(Map.of("a", ONE));
        Map<String, JsonValue> plain = new LinkedHashMap<>(Map.of("a", ONE));
        assertEquals(plain, a);
        assertEquals(a, plain);
        assertEquals(plain.hashCode(), a.hashCode());
        assertNotEquals(a, Map.of("a", TWO));
    }

    private static Map<String, JsonValue> orderedMembers(String k1, JsonValue v1, String k2, JsonValue v2) {
        var members = new LinkedHashMap<String, JsonValue>();
        members.put(k1, v1);
        members.put(k2, v2);
        return members;
    }
}
