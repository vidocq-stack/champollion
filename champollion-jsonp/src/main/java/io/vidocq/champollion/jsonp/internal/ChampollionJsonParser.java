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
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParsingException;

import java.io.Reader;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Pull-based Jakarta JSON-P 2.1 parser, based on {@link JsonTokenizer}.
 *
 * <p>The parser maintains a minimal state machine: on each {@link #next()}
 * call it consumes one or more tokens and emits exactly one {@link Event}.</p>
 *
 * <p>P13 — the state machine is a scalar {@link Scope} field plus a
 * <em>bit-stack</em> of open containers (one bit per nesting level:
 * 1&nbsp;=&nbsp;object, 0&nbsp;=&nbsp;array). A parent scope is always already
 * advanced to its {@code *_COMMA} state when a child container opens, so the
 * container bit alone reconstructs the parent state on close — no
 * {@code Deque} push/pop on the per-event hot path.</p>
 *
 * <p>P12 — the value accessors ({@code getString}, {@code getInt}, etc.)
 * delegate to the tokenizer's lazy pending value: nothing is materialized
 * while the event stream is merely drained.</p>
 */
public final class ChampollionJsonParser implements JsonParser {

    private enum Scope { ROOT, OBJECT_START, OBJECT_KEY, OBJECT_COLON, OBJECT_VALUE, OBJECT_COMMA,
                         ARRAY_START, ARRAY_VALUE, ARRAY_COMMA, DONE }

    private JsonTokenizer tokenizer;

    /** Current micro-state (replaces the former scope deque's top). */
    private Scope state = Scope.ROOT;
    /** Container-type bits of the open nesting levels (1 = object, 0 = array). */
    private long[] nestBits = new long[1];
    /** Number of open containers. */
    private int depth = 0;

    private Event next;
    private boolean nextReady;
    private Event lastEvent;

    public ChampollionJsonParser(Reader reader) {
        this.tokenizer = new JsonReaderTokenizer(reader);
    }

    /** P10.1 — String fast path: no Reader, no intermediate char[]. */
    public ChampollionJsonParser(String src) {
        this.tokenizer = new JsonStringTokenizer(src);
    }

    /**
     * Recycles this parser for a new {@link Reader}. The tokenizer is
     * reallocated (light instance: ~32 B + char[BUF_SIZE]) to preserve the
     * {@code final Reader} that lets HotSpot inline {@code read()} in the
     * ASCII scan loop. Does <em>not</em> close the old reader.
     */
    public void reset(Reader newReader) {
        this.tokenizer = new JsonReaderTokenizer(newReader);
        clearState();
    }

    /** P10.1 — {@code reset(String)} variant: tokenizer in direct String mode. */
    public void reset(String src) {
        this.tokenizer = new JsonStringTokenizer(src);
        clearState();
    }

    private void clearState() {
        this.state = Scope.ROOT;
        this.depth = 0;
        this.next = null;
        this.nextReady = false;
        this.lastEvent = null;
    }

    private void pushNest(boolean object) {
        int idx = depth >>> 6;
        if (idx == nestBits.length) {
            nestBits = java.util.Arrays.copyOf(nestBits, nestBits.length * 2);
        }
        long bit = 1L << (depth & 63);
        if (object) nestBits[idx] |= bit;
        else nestBits[idx] &= ~bit;
        depth++;
    }

    /** Pops the closing container and returns the parent's resume scope. */
    private Scope popNest() {
        depth--;
        if (depth == 0) return Scope.DONE;
        int top = depth - 1;
        boolean object = (nestBits[top >>> 6] & (1L << (top & 63))) != 0;
        return object ? Scope.OBJECT_COMMA : Scope.ARRAY_COMMA;
    }

    @Override
    public boolean hasNext() {
        if (nextReady) return next != null;
        next = computeNext();
        nextReady = true;
        return next != null;
    }

    @Override
    public Event next() {
        if (!hasNext()) throw new NoSuchElementException("No more JSON events");
        nextReady = false;
        lastEvent = next;
        return next;
    }

    private Event computeNext() {
        // Separator scopes (colon, comma) loop back instead of recursing.
        while (true) {
            switch (state) {
                case DONE -> {
                    // ROOT post-value: wait for EOF or throw
                    JsonToken trailing = tokenizer.next();
                    if (trailing == JsonToken.EOF) return null;
                    throw parsing("Unexpected token after root value");
                }
                case ROOT -> {
                    JsonToken t = tokenizer.next();
                    if (t == JsonToken.EOF) throw parsing("Empty input");
                    Event e = consumeValueToken(t);
                    if (e == null) throw parsing("Unexpected token at root");
                    // If the root did not open a container, we are done.
                    if (state == Scope.ROOT) state = Scope.DONE;
                    return e;
                }
                case OBJECT_START -> {
                    JsonToken t = tokenizer.next();
                    if (t == JsonToken.END_OBJECT) {
                        state = popNest();
                        return Event.END_OBJECT;
                    }
                    return consumeKey(t);
                }
                case OBJECT_KEY -> {
                    return consumeKey(tokenizer.next());
                }
                case OBJECT_COLON -> {
                    if (tokenizer.next() != JsonToken.NAME_SEPARATOR) {
                        throw parsing("Expected ':' after key");
                    }
                    state = Scope.OBJECT_VALUE;
                }
                case OBJECT_VALUE, ARRAY_VALUE -> {
                    Event e = consumeValueToken(tokenizer.next());
                    if (e == null) throw parsing("Expected JSON value");
                    return e;
                }
                case OBJECT_COMMA -> {
                    JsonToken t = tokenizer.next();
                    if (t == JsonToken.END_OBJECT) {
                        state = popNest();
                        return Event.END_OBJECT;
                    }
                    if (t != JsonToken.VALUE_SEPARATOR) {
                        throw parsing("Expected ',' or '}' in object");
                    }
                    state = Scope.OBJECT_KEY;
                }
                case ARRAY_START -> {
                    JsonToken t = tokenizer.next();
                    if (t == JsonToken.END_ARRAY) {
                        state = popNest();
                        return Event.END_ARRAY;
                    }
                    Event e = consumeValueToken(t);
                    if (e == null) throw parsing("Unexpected token in array");
                    return e;
                }
                case ARRAY_COMMA -> {
                    JsonToken t = tokenizer.next();
                    if (t == JsonToken.END_ARRAY) {
                        state = popNest();
                        return Event.END_ARRAY;
                    }
                    if (t != JsonToken.VALUE_SEPARATOR) {
                        throw parsing("Expected ',' or ']' in array");
                    }
                    state = Scope.ARRAY_VALUE;
                }
            }
        }
    }

    private Event consumeKey(JsonToken t) {
        if (t != JsonToken.STRING) {
            throw parsing("Expected string key in object");
        }
        state = Scope.OBJECT_COLON;
        return Event.KEY_NAME;
    }

    /**
     * Consumes a value token and emits the corresponding event. Updates the
     * state (and the nesting bit-stack for containers). Returns {@code null}
     * if the token is not a value token.
     */
    private Event consumeValueToken(JsonToken t) {
        return switch (t) {
            case START_OBJECT -> {
                advanceAfterValue();
                pushNest(true);
                state = Scope.OBJECT_START;
                yield Event.START_OBJECT;
            }
            case START_ARRAY -> {
                advanceAfterValue();
                pushNest(false);
                state = Scope.ARRAY_START;
                yield Event.START_ARRAY;
            }
            case STRING -> { advanceAfterValue(); yield Event.VALUE_STRING; }
            case NUMBER -> { advanceAfterValue(); yield Event.VALUE_NUMBER; }
            case TRUE -> { advanceAfterValue(); yield Event.VALUE_TRUE; }
            case FALSE -> { advanceAfterValue(); yield Event.VALUE_FALSE; }
            case NULL -> { advanceAfterValue(); yield Event.VALUE_NULL; }
            default -> null;
        };
    }

    /** After consuming a value (or opening a container), advances the scope. */
    private void advanceAfterValue() {
        switch (state) {
            case OBJECT_VALUE -> state = Scope.OBJECT_COMMA;
            case ARRAY_START, ARRAY_VALUE -> state = Scope.ARRAY_COMMA;
            case ROOT -> { /* handled by the ROOT case after return */ }
            default -> {}
        }
    }

    @Override public String getString() {
        if (lastEvent == Event.VALUE_STRING || lastEvent == Event.KEY_NAME
                || lastEvent == Event.VALUE_NUMBER) {
            return tokenizer.currentString();
        }
        throw new IllegalStateException("getString() not valid for event " + lastEvent);
    }

    @Override public boolean isIntegralNumber() {
        requireNumber();
        return tokenizer.currentIntegral();
    }

    @Override public int getInt() {
        requireNumber();
        // Same low-order-32-bits semantics as getBigDecimal().intValue().
        return (int) tokenizer.currentLong();
    }

    @Override public long getLong() {
        requireNumber();
        return tokenizer.currentLong();
    }

    @Override public BigDecimal getBigDecimal() {
        requireNumber();
        return tokenizer.currentBigDecimal();
    }

    @Override public JsonLocation getLocation() {
        return new SimpleLocation(tokenizer.line(), tokenizer.column(), tokenizer.offset());
    }

    @Override public Event currentEvent() {
        return lastEvent;
    }

    @Override public JsonValue getValue() {
        // Spec §3.10 (2.1): returns the value at the current position. For
        // START_OBJECT/START_ARRAY, this is equivalent to getObject()/getArray().
        // For KEY_NAME, returns the key string. For VALUE_*, the corresponding
        // scalar JsonValue. If no event has been read yet, advance automatically
        // (TCK 2.1 case).
        if (lastEvent == null) {
            if (!hasNext()) {
                throw new IllegalStateException("getValue() : empty input");
            }
            next();
        }
        return switch (lastEvent) {
            case START_OBJECT -> readObjectMembers();
            case START_ARRAY -> readArrayElements();
            case KEY_NAME, VALUE_STRING -> new ChampollionJsonString(tokenizer.currentString());
            case VALUE_NUMBER -> ChampollionJsonNumber.of(tokenizer.currentString());
            case VALUE_TRUE -> JsonValue.TRUE;
            case VALUE_FALSE -> JsonValue.FALSE;
            case VALUE_NULL -> JsonValue.NULL;
            case END_OBJECT, END_ARRAY -> throw new IllegalStateException("getValue() not valid for " + lastEvent);
        };
    }

    @Override public JsonObject getObject() {
        // §3.10.2.1 — also accept a fresh parser (not yet next'ed): pre-next to
        // reach START_OBJECT (TCK Jersey compatibility + custom JsonbDeserializer
        // code that calls getObject() directly).
        if (lastEvent == null) {
            Event ev = next();
            if (ev != Event.START_OBJECT) {
                throw new IllegalStateException("getObject() expected START_OBJECT, got " + ev);
            }
        } else if (lastEvent != Event.START_OBJECT) {
            throw new IllegalStateException("getObject() requires last event = START_OBJECT, got " + lastEvent);
        }
        return readObjectMembers();
    }

    @Override public JsonArray getArray() {
        if (lastEvent == null) {
            Event ev = next();
            if (ev != Event.START_ARRAY) {
                throw new IllegalStateException("getArray() expected START_ARRAY, got " + ev);
            }
        } else if (lastEvent != Event.START_ARRAY) {
            throw new IllegalStateException("getArray() requires last event = START_ARRAY, got " + lastEvent);
        }
        return readArrayElements();
    }

    /** Reads members until END_OBJECT (the initial START_OBJECT has already been consumed). */
    private JsonObject readObjectMembers() {
        var map = new java.util.LinkedHashMap<String, JsonValue>();
        while (true) {
            Event e = next();
            if (e == Event.END_OBJECT) return ChampollionJsonObject.of(map);
            if (e != Event.KEY_NAME) {
                throw new IllegalStateException("Expected KEY_NAME or END_OBJECT, got " + e);
            }
            String key = tokenizer.currentString();
            Event ve = next();
            map.put(key, readScalarOrStructure(ve));
        }
    }

    /** Reads elements until END_ARRAY (the initial START_ARRAY has already been consumed). */
    private JsonArray readArrayElements() {
        var list = new java.util.ArrayList<JsonValue>();
        while (true) {
            Event e = next();
            if (e == Event.END_ARRAY) return ChampollionJsonArray.of(list);
            list.add(readScalarOrStructure(e));
        }
    }

    /** Builds a JsonValue from the current (already read) event. */
    private JsonValue readScalarOrStructure(Event e) {
        return switch (e) {
            case START_OBJECT -> readObjectMembers();
            case START_ARRAY -> readArrayElements();
            case VALUE_STRING -> new ChampollionJsonString(tokenizer.currentString());
            case VALUE_NUMBER -> ChampollionJsonNumber.of(tokenizer.currentString());
            case VALUE_TRUE -> JsonValue.TRUE;
            case VALUE_FALSE -> JsonValue.FALSE;
            case VALUE_NULL -> JsonValue.NULL;
            default -> throw new IllegalStateException("Unexpected event during value read: " + e);
        };
    }

    @Override public Stream<JsonValue> getArrayStream() {
        if (lastEvent != Event.START_ARRAY) {
            throw new IllegalStateException("getArrayStream() requires last event = START_ARRAY, got " + lastEvent);
        }
        // Simple implementation: reads the whole array into memory, then streams over it.
        return readArrayElements().stream().map(v -> v);
    }

    @Override public Stream<java.util.Map.Entry<String, JsonValue>> getObjectStream() {
        if (lastEvent != Event.START_OBJECT) {
            throw new IllegalStateException("getObjectStream() requires last event = START_OBJECT, got " + lastEvent);
        }
        return readObjectMembers().entrySet().stream();
    }

    @Override public Stream<JsonValue> getValueStream() {
        // Spec §3.10: getValueStream() must be called at the document root before
        // any next(). If we are inside an object/array, throw IllegalStateException.
        if (lastEvent != null) {
            throw new IllegalStateException("getValueStream() must be called before any next() at root level");
        }
        return Stream.of(getValue());
    }

    @Override public void skipArray() {
        skipUntilCloseOf(Event.START_ARRAY, Event.END_ARRAY);
    }

    @Override public void skipObject() {
        skipUntilCloseOf(Event.START_OBJECT, Event.END_OBJECT);
    }

    private void skipUntilCloseOf(Event openEvent, Event closeEvent) {
        if (lastEvent != openEvent) return;
        int depth = 1;
        while (depth > 0 && hasNext()) {
            Event e = next();
            if (e == openEvent) depth++;
            else if (e == closeEvent) depth--;
        }
    }

    @Override public void close() {
        // Spec §3.6: closes the underlying Reader/InputStream; propagates
        // IOException as JsonException.
        state = Scope.DONE;
        depth = 0;
        tokenizer.close();
    }

    private void requireNumber() {
        if (lastEvent != Event.VALUE_NUMBER) {
            throw new IllegalStateException("number accessor not valid for event " + lastEvent);
        }
    }

    private JsonParsingException parsing(String message) {
        return new JsonParsingException(
                message + " (at line " + tokenizer.line() + ", column " + tokenizer.column() + ")",
                getLocation());
    }

    private record SimpleLocation(long lineNumber, long columnNumber, long streamOffset) implements JsonLocation {
        @Override public long getLineNumber() { return lineNumber; }
        @Override public long getColumnNumber() { return columnNumber; }
        @Override public long getStreamOffset() { return streamOffset; }
    }
}
