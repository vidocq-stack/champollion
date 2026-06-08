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
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-P 2.1 object model reader based on {@link ChampollionJsonParser}.
 *
 * <p>Stack-based iterative construction to avoid recursion proportional to
 * document depth — virtual-thread-friendly and resistant to very deep JSON (see
 * JSONTestSuite corpus {@code i_structure_500_nested_arrays.json}).</p>
 */
public final class ChampollionJsonReader implements JsonReader {

    private final JsonParser parser;
    private final jakarta.json.JsonConfig.KeyStrategy keyStrategy;
    private boolean consumed;

    public ChampollionJsonReader(Reader reader) {
        this(new ChampollionJsonParser(reader), jakarta.json.JsonConfig.KeyStrategy.LAST);
    }

    public ChampollionJsonReader(JsonParser parser) {
        this(parser, jakarta.json.JsonConfig.KeyStrategy.LAST);
    }

    /** Allows configuring the duplicate-key strategy (Spec 2.1 §4.6). */
    public ChampollionJsonReader(JsonParser parser, jakarta.json.JsonConfig.KeyStrategy keyStrategy) {
        this.parser = parser;
        this.keyStrategy = keyStrategy == null ? jakarta.json.JsonConfig.KeyStrategy.LAST : keyStrategy;
    }

    @Override public JsonStructure read() {
        JsonValue v = readValue();
        if (!(v instanceof JsonStructure s)) {
            throw new JsonException("Root JSON value is not an object or array");
        }
        return s;
    }

    @Override public JsonObject readObject() {
        JsonStructure s = read();
        if (!(s instanceof JsonObject o)) {
            throw new JsonException("readObject() called but root is not a JSON object");
        }
        return o;
    }
    @Override public JsonArray readArray() {
        JsonStructure s = read();
        if (!(s instanceof JsonArray a)) {
            throw new JsonException("readArray() called but root is not a JSON array");
        }
        return a;
    }

    @Override public JsonValue readValue() {
        if (consumed) throw new IllegalStateException("read* methods cannot be invoked twice");
        consumed = true;

        if (!parser.hasNext()) throw new JsonException("Empty input");
        JsonParser.Event e = parser.next();
        JsonValue root = parseValue(e);

        if (parser.hasNext()) {
            // The parser already rejects multi-roots, but we keep a safety check.
            throw new JsonException("Unexpected trailing content after root value");
        }
        return root;
    }

    private JsonValue parseValue(JsonParser.Event first) {
        // Stack of "frames": each frame is either an object builder (with the
        // current name) or an array builder. Iterative construction.
        Deque<Frame> stack = new ArrayDeque<>();
        JsonValue currentValue = null;
        JsonParser.Event e = first;

        while (true) {
            switch (e) {
                case START_OBJECT -> stack.push(new ObjectFrame());
                case START_ARRAY -> stack.push(new ArrayFrame());
                case KEY_NAME -> ((ObjectFrame) stack.peek()).pendingKey = parser.getString();
                case VALUE_STRING -> currentValue = new ChampollionJsonString(parser.getString());
                case VALUE_NUMBER -> currentValue = ChampollionJsonNumber.of(parser.getString());
                case VALUE_TRUE -> currentValue = JsonValue.TRUE;
                case VALUE_FALSE -> currentValue = JsonValue.FALSE;
                case VALUE_NULL -> currentValue = JsonValue.NULL;
                case END_OBJECT -> {
                    var frame = (ObjectFrame) stack.pop();
                    currentValue = ChampollionJsonObject.of(frame.members);
                }
                case END_ARRAY -> {
                    var frame = (ArrayFrame) stack.pop();
                    currentValue = ChampollionJsonArray.of(frame.values);
                }
            }

            // If the current event produced a value (END_* or VALUE_*), attach it to the parent.
            if (currentValue != null) {
                if (stack.isEmpty()) return currentValue;
                Frame parent = stack.peek();
                if (parent instanceof ObjectFrame of) {
                    String key = of.pendingKey;
                    switch (keyStrategy) {
                        case LAST -> of.members.put(key, currentValue);
                        case FIRST -> of.members.putIfAbsent(key, currentValue);
                        case NONE -> {
                            if (of.members.putIfAbsent(key, currentValue) != null) {
                                throw new JsonException("Duplicate key '" + key + "' (KeyStrategy.NONE)");
                            }
                        }
                    }
                    of.pendingKey = null;
                } else {
                    ((ArrayFrame) parent).values.add(currentValue);
                }
                currentValue = null;
            }

            if (!parser.hasNext()) {
                if (!stack.isEmpty()) throw new JsonException("Unterminated container");
                return currentValue;
            }
            e = parser.next();
        }
    }

    @Override public void close() {
        // Mark the reader as consumed so any later read*() throws IllegalStateException
        // (Spec §3.6).
        consumed = true;
        parser.close();
    }

    private sealed interface Frame permits ObjectFrame, ArrayFrame {}

    private static final class ObjectFrame implements Frame {
        final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        String pendingKey;
    }

    private static final class ArrayFrame implements Frame {
        final java.util.ArrayList<JsonValue> values = new java.util.ArrayList<>();
    }
}
