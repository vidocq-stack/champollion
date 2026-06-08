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

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonPointer;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import java.util.Objects;

/**
 * Immutable {@link JsonPatch} implementation conforming to RFC 6902.
 *
 * <p>Application semantics: each operation is applied to the current state
 * (the result of previous operations). Any error (missing path for replace,
 * failing test, etc.) aborts application with a {@link JsonException}.</p>
 */
public final class ChampollionJsonPatch implements JsonPatch {

    private final JsonArray operations;

    public ChampollionJsonPatch(JsonArray operations) {
        Objects.requireNonNull(operations, "operations is null");
        this.operations = operations;
    }

    @Override public <T extends JsonStructure> T apply(T target) {
        JsonStructure current = target;
        for (JsonValue op : operations) {
            if (!(op instanceof JsonObject opObj)) {
                throw new JsonException("Patch operation must be an object");
            }
            current = applyOne(current, opObj);
        }
        @SuppressWarnings("unchecked")
        T result = (T) current;
        return result;
    }

    @Override public JsonArray toJsonArray() { return operations; }

    private JsonStructure applyOne(JsonStructure target, JsonObject op) {
        String name = requireString(op, "op");
        return switch (name) {
            case "add" -> applyAdd(target, op);
            case "remove" -> applyRemove(target, op);
            case "replace" -> applyReplace(target, op);
            case "move" -> applyMove(target, op);
            case "copy" -> applyCopy(target, op);
            case "test" -> applyTest(target, op);
            default -> throw new JsonException("Unknown patch op: " + name);
        };
    }

    private JsonStructure applyAdd(JsonStructure target, JsonObject op) {
        var pointer = pointerOf(op, "path");
        JsonValue value = requireMember(op, "value");
        return pointer.add(target, value);
    }

    private JsonStructure applyRemove(JsonStructure target, JsonObject op) {
        var pointer = pointerOf(op, "path");
        return pointer.remove(target);
    }

    private JsonStructure applyReplace(JsonStructure target, JsonObject op) {
        var pointer = pointerOf(op, "path");
        JsonValue value = requireMember(op, "value");
        return pointer.replace(target, value);
    }

    private JsonStructure applyMove(JsonStructure target, JsonObject op) {
        var from = pointerOf(op, "from");
        var path = pointerOf(op, "path");
        JsonValue moved = from.getValue(target);
        JsonStructure removed = from.remove(target);
        return path.add(removed, moved);
    }

    private JsonStructure applyCopy(JsonStructure target, JsonObject op) {
        var from = pointerOf(op, "from");
        var path = pointerOf(op, "path");
        JsonValue copied = from.getValue(target);
        return path.add(target, copied);
    }

    private JsonStructure applyTest(JsonStructure target, JsonObject op) {
        var path = pointerOf(op, "path");
        JsonValue expected = requireMember(op, "value");
        JsonValue actual = path.getValue(target);
        if (!equalsByValue(actual, expected)) {
            throw new JsonException("Test op failed at " + path + ": expected " + expected + ", got " + actual);
        }
        return target;
    }

    /**
     * Value-by-value equality (RFC 6902 §4.6). For numbers, numeric comparison
     * (1 == 1.0). For containers, structural equivalence. For string/bool/null:
     * value identity equality.
     */
    static boolean equalsByValue(JsonValue a, JsonValue b) {
        if (a == b) return true;
        if (a.getValueType() != b.getValueType()) return false;
        return switch (a.getValueType()) {
            case OBJECT -> equalsObject((JsonObject) a, (JsonObject) b);
            case ARRAY -> equalsArray((JsonArray) a, (JsonArray) b);
            case STRING, NUMBER -> a.equals(b);
            case TRUE, FALSE, NULL -> true;
        };
    }

    private static boolean equalsObject(JsonObject a, JsonObject b) {
        if (a.size() != b.size()) return false;
        for (var e : a.entrySet()) {
            JsonValue other = b.get(e.getKey());
            if (other == null || !equalsByValue(e.getValue(), other)) return false;
        }
        return true;
    }

    private static boolean equalsArray(JsonArray a, JsonArray b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equalsByValue(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private JsonPointer pointerOf(JsonObject op, String memberName) {
        return new ChampollionJsonPointer(requireString(op, memberName));
    }

    private static JsonValue requireMember(JsonObject op, String name) {
        JsonValue v = op.get(name);
        if (v == null) throw new JsonException("Patch op missing required member '" + name + "'");
        return v;
    }

    private static String requireString(JsonObject op, String name) {
        JsonValue v = requireMember(op, name);
        if (!(v instanceof jakarta.json.JsonString s)) {
            throw new JsonException("Member '" + name + "' must be a string");
        }
        return s.getString();
    }
}
