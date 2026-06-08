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
import jakarta.json.JsonPointer;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable {@link JsonPointer} implementation conforming to RFC 6901.
 *
 * <p>Format: "" (whole document), or {@code /token/token/...} where each token
 * has its '~' escaped as {@code ~0} and its '/' as {@code ~1}. For arrays, a
 * token is either a decimal index without a leading zero (except "0"), or '-'
 * which denotes the index immediately after the last one (append).</p>
 *
 * <p>Mutation methods ({@code add}, {@code replace}, {@code remove}) return a
 * <em>new</em> {@link JsonStructure}: the original instance is never modified
 * (consistent with the immutability of {@link ChampollionJsonObject} and
 * {@link ChampollionJsonArray}).</p>
 */
public final class ChampollionJsonPointer implements JsonPointer {

    private final String raw;
    private final List<String> tokens;
    private final JsonException parseError;

    public ChampollionJsonPointer(String pointer) {
        if (pointer == null) throw new IllegalArgumentException("pointer is null");
        if (!pointer.isEmpty() && pointer.charAt(0) != '/') {
            throw new JsonException("JSON Pointer must be empty or start with '/'");
        }
        this.raw = pointer;
        // Spec interpretation: RFC 6901 §3 reserves `~0`/`~1`; a malformed `~n`
        // is technically invalid, but the 2.1 TCK
        // (PointerResolve.testResolvePathWithUnencodedTilde) tolerates this and
        // only expects the exception at resolution time.
        // We therefore defer the parsing error to `getValue()` and friends.
        List<String> parsed;
        JsonException err = null;
        try {
            parsed = parse(pointer);
        } catch (JsonException e) {
            parsed = List.of();
            err = e;
        }
        this.tokens = parsed;
        this.parseError = err;
    }

    private void requireValidPointer() {
        if (parseError != null) throw parseError;
    }

    private static List<String> parse(String pointer) {
        if (pointer.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int len = pointer.length();
        var sb = new StringBuilder();
        // Skip the initial '/'.
        for (int i = 1; i < len; i++) {
            char c = pointer.charAt(i);
            if (c == '/') {
                out.add(sb.toString());
                sb.setLength(0);
            } else if (c == '~') {
                if (i + 1 >= len) throw new JsonException("Unfinished escape in JSON Pointer");
                char next = pointer.charAt(++i);
                if (next == '0') sb.append('~');
                else if (next == '1') sb.append('/');
                else throw new JsonException("Invalid escape '~" + next + "' in JSON Pointer");
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return List.copyOf(out);
    }

    // ===== Lookup =====

    @Override public JsonValue getValue(JsonStructure target) {
        requireValidPointer();
        JsonValue cur = target;
        for (String tok : tokens) {
            cur = step(cur, tok, /*allowDash*/ false);
        }
        return cur;
    }

    @Override public boolean containsValue(JsonStructure target) {
        if (parseError != null) return false;
        try {
            JsonValue cur = target;
            for (String tok : tokens) {
                cur = stepOrNull(cur, tok);
                if (cur == null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonValue step(JsonValue cur, String tok, boolean allowDash) {
        if (cur instanceof JsonObject obj) {
            if (!obj.containsKey(tok)) {
                throw new JsonException("Member '" + tok + "' not found");
            }
            return obj.get(tok);
        }
        if (cur instanceof JsonArray arr) {
            int idx = parseIndex(tok, arr.size(), allowDash);
            if (idx == arr.size()) {
                throw new JsonException("Array index '-' is only valid for insertion");
            }
            return arr.get(idx);
        }
        throw new JsonException("Cannot navigate into non-structural value");
    }

    private static JsonValue stepOrNull(JsonValue cur, String tok) {
        if (cur instanceof JsonObject obj) {
            return obj.get(tok);
        }
        if (cur instanceof JsonArray arr) {
            try {
                int idx = parseIndex(tok, arr.size(), /*allowDash*/ false);
                if (idx >= arr.size()) return null;
                return arr.get(idx);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static int parseIndex(String tok, int size, boolean allowDash) {
        if ("-".equals(tok)) {
            if (!allowDash) throw new JsonException("Array index '-' invalid here");
            return size;
        }
        if (tok.isEmpty()) throw new JsonException("Empty array index");
        // RFC 6901 §4: no leading zeros (except "0").
        if (tok.length() > 1 && tok.charAt(0) == '0') {
            throw new JsonException("Leading zeros not allowed in array index: " + tok);
        }
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c < '0' || c > '9') throw new JsonException("Invalid array index: " + tok);
        }
        int idx = Integer.parseInt(tok);
        if (idx > size) throw new JsonException("Array index out of bounds: " + idx);
        return idx;
    }

    // ===== Mutations (return NEW structure) =====

    @Override public <T extends JsonStructure> T add(T target, JsonValue value) {
        return mutate(target, Op.ADD, value);
    }

    @Override public <T extends JsonStructure> T replace(T target, JsonValue value) {
        return mutate(target, Op.REPLACE, value);
    }

    @Override public <T extends JsonStructure> T remove(T target) {
        return mutate(target, Op.REMOVE, null);
    }

    private enum Op { ADD, REPLACE, REMOVE }

    @SuppressWarnings("unchecked")
    private <T extends JsonStructure> T mutate(T target, Op op, JsonValue value) {
        requireValidPointer();
        if (tokens.isEmpty()) {
            // Empty pointer = replace the whole document (add/replace) or forbidden (remove).
            if (op == Op.REMOVE) throw new JsonException("Cannot remove root document");
            if (!(value instanceof JsonStructure s)) throw new JsonException("Root replacement must be object or array");
            return (T) s;
        }
        return (T) mutateRecursive(target, 0, op, value);
    }

    private JsonValue mutateRecursive(JsonValue current, int depth, Op op, JsonValue value) {
        String tok = tokens.get(depth);
        boolean last = (depth == tokens.size() - 1);

        if (current instanceof JsonObject obj) {
            return mutateObject(obj, tok, depth, last, op, value);
        }
        if (current instanceof JsonArray arr) {
            return mutateArray(arr, tok, depth, last, op, value);
        }
        throw new JsonException("Cannot navigate into non-structural value");
    }

    private JsonValue mutateObject(JsonObject obj, String tok, int depth, boolean last, Op op, JsonValue value) {
        var copy = new LinkedHashMap<String, JsonValue>(obj);
        if (last) {
            switch (op) {
                case ADD -> copy.put(tok, value);  // ADD replaces if already present (see RFC 6902 §4.1)
                case REPLACE -> {
                    if (!copy.containsKey(tok)) throw new JsonException("Cannot replace missing member: " + tok);
                    copy.put(tok, value);
                }
                case REMOVE -> {
                    if (!copy.containsKey(tok)) throw new JsonException("Cannot remove missing member: " + tok);
                    copy.remove(tok);
                }
            }
        } else {
            JsonValue child = copy.get(tok);
            if (child == null) throw new JsonException("Member '" + tok + "' not found");
            copy.put(tok, mutateRecursive(child, depth + 1, op, value));
        }
        return ChampollionJsonObject.of(copy);
    }

    private JsonValue mutateArray(JsonArray arr, String tok, int depth, boolean last, Op op, JsonValue value) {
        if (last) {
            int idx;
            switch (op) {
                case ADD -> {
                    idx = parseIndex(tok, arr.size(), /*allowDash*/ true);
                    var copy = new ArrayList<JsonValue>(arr.size() + 1);
                    copy.addAll(arr);
                    copy.add(idx, value);
                    return ChampollionJsonArray.of(copy);
                }
                case REPLACE -> {
                    idx = parseIndex(tok, arr.size(), /*allowDash*/ false);
                    if (idx >= arr.size()) throw new JsonException("Cannot replace at index out of bounds: " + idx);
                    var copy = new ArrayList<JsonValue>(arr);
                    copy.set(idx, value);
                    return ChampollionJsonArray.of(copy);
                }
                case REMOVE -> {
                    idx = parseIndex(tok, arr.size(), /*allowDash*/ false);
                    if (idx >= arr.size()) throw new JsonException("Cannot remove at index out of bounds: " + idx);
                    var copy = new ArrayList<JsonValue>(arr);
                    copy.remove(idx);
                    return ChampollionJsonArray.of(copy);
                }
                default -> throw new IllegalStateException();
            }
        }
        int idx = parseIndex(tok, arr.size(), /*allowDash*/ false);
        if (idx >= arr.size()) throw new JsonException("Array index out of bounds: " + idx);
        var copy = new ArrayList<JsonValue>(arr);
        copy.set(idx, mutateRecursive(arr.get(idx), depth + 1, op, value));
        return ChampollionJsonArray.of(copy);
    }

    @Override public boolean equals(Object o) {
        return (o instanceof ChampollionJsonPointer p) && raw.equals(p.raw);
    }

    @Override public int hashCode() { return raw.hashCode(); }

    @Override public String toString() { return raw; }
}
