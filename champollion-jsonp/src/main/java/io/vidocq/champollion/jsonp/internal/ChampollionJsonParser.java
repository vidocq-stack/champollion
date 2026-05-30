package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParsingException;

import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Pull-based Jakarta JSON-P 2.1 parser, based on {@link JsonTokenizer}.
 *
 * <p>The parser maintains a stack of scopes ({@link Scope}) and a minimal state
 * machine: on each {@link #next()} call it consumes one or more tokens and emits
 * exactly one {@link Event}.</p>
 *
 * <p>The value accessors ({@code getString}, {@code getInt}, etc.) read the
 * internal buffers {@code lastString} / {@code lastNumber} populated by {@code next()}.</p>
 */
public final class ChampollionJsonParser implements JsonParser {

    private enum Scope { ROOT, OBJECT_START, OBJECT_KEY, OBJECT_COLON, OBJECT_VALUE, OBJECT_COMMA,
                         ARRAY_START, ARRAY_VALUE, ARRAY_COMMA, DONE }

    private JsonTokenizer tokenizer;
    // Initial capacity 4 — the average nesting depth of production JSON stays
    // well below 8; the default ArrayDeque (16) allocates an unnecessarily
    // large backing array on the read hot path.
    private final Deque<Scope> scopes = new ArrayDeque<>(4);

    private Event next;
    private boolean nextReady;
    private String lastString;
    private String lastNumber;
    private Event lastEvent;

    public ChampollionJsonParser(Reader reader) {
        this.tokenizer = new JsonReaderTokenizer(reader);
        this.scopes.push(Scope.ROOT);
    }

    /** P10.1 — String fast path: no Reader, no intermediate char[]. */
    public ChampollionJsonParser(String src) {
        this.tokenizer = new JsonStringTokenizer(src);
        this.scopes.push(Scope.ROOT);
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
        this.scopes.clear();
        this.scopes.push(Scope.ROOT);
        this.next = null;
        this.nextReady = false;
        this.lastString = null;
        this.lastNumber = null;
        this.lastEvent = null;
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
        // ROOT post-value: wait for EOF or throw
        if (scopes.peek() == Scope.DONE) {
            JsonToken trailing = tokenizer.next();
            if (trailing == JsonToken.Eof.INSTANCE) return null;
            throw parsing("Unexpected token after root value");
        }

        Scope scope = scopes.peek();
        return switch (scope) {
            case ROOT -> parseRootValue();
            case OBJECT_START -> parseObjectKeyOrEnd();
            case OBJECT_KEY -> parseObjectKey();
            case OBJECT_COLON -> parseObjectColon();
            case OBJECT_VALUE -> parseValue(/*inObject*/ true);
            case OBJECT_COMMA -> parseObjectCommaOrEnd();
            case ARRAY_START -> parseArrayValueOrEnd();
            case ARRAY_VALUE -> parseValue(/*inObject*/ false);
            case ARRAY_COMMA -> parseArrayCommaOrEnd();
            case DONE -> null;
        };
    }

    private Event parseRootValue() {
        JsonToken t = tokenizer.next();
        if (t == JsonToken.Eof.INSTANCE) {
            throw parsing("Empty input");
        }
        Event e = consumeValueToken(t, /*topLevel*/ true);
        if (e == null) throw parsing("Unexpected token at root");
        // If the root did not open a scope, we are done.
        if (scopes.peek() == Scope.ROOT) {
            scopes.pop();
            scopes.push(Scope.DONE);
        }
        return e;
    }

    private Event parseObjectKeyOrEnd() {
        JsonToken t = tokenizer.next();
        if (t == JsonToken.EndObject.INSTANCE) {
            scopes.pop();
            transitionAfterValue();
            return Event.END_OBJECT;
        }
        return consumeKey(t);
    }

    private Event parseObjectKey() {
        JsonToken t = tokenizer.next();
        return consumeKey(t);
    }

    private Event consumeKey(JsonToken t) {
        if (!(t instanceof JsonToken.StringToken s)) {
            throw parsing("Expected string key in object");
        }
        lastString = s.value();
        scopes.pop();
        scopes.push(Scope.OBJECT_COLON);
        return Event.KEY_NAME;
    }

    private Event parseObjectColon() {
        JsonToken t = tokenizer.next();
        if (t != JsonToken.NameSeparator.INSTANCE) {
            throw parsing("Expected ':' after key");
        }
        scopes.pop();
        scopes.push(Scope.OBJECT_VALUE);
        return computeNext();
    }

    private Event parseObjectCommaOrEnd() {
        JsonToken t = tokenizer.next();
        if (t == JsonToken.EndObject.INSTANCE) {
            scopes.pop();
            transitionAfterValue();
            return Event.END_OBJECT;
        }
        if (t != JsonToken.ValueSeparator.INSTANCE) {
            throw parsing("Expected ',' or '}' in object");
        }
        scopes.pop();
        scopes.push(Scope.OBJECT_KEY);
        return computeNext();
    }

    private Event parseArrayValueOrEnd() {
        JsonToken t = tokenizer.next();
        if (t == JsonToken.EndArray.INSTANCE) {
            scopes.pop();
            transitionAfterValue();
            return Event.END_ARRAY;
        }
        Event e = consumeValueToken(t, /*topLevel*/ false);
        if (e == null) throw parsing("Unexpected token in array");
        return e;
    }

    private Event parseValue(boolean inObject) {
        JsonToken t = tokenizer.next();
        Event e = consumeValueToken(t, /*topLevel*/ false);
        if (e == null) throw parsing("Expected JSON value");
        return e;
    }

    private Event parseArrayCommaOrEnd() {
        JsonToken t = tokenizer.next();
        if (t == JsonToken.EndArray.INSTANCE) {
            scopes.pop();
            transitionAfterValue();
            return Event.END_ARRAY;
        }
        if (t != JsonToken.ValueSeparator.INSTANCE) {
            throw parsing("Expected ',' or ']' in array");
        }
        scopes.pop();
        scopes.push(Scope.ARRAY_VALUE);
        return computeNext();
    }

    /**
     * Consumes a value token and emits the corresponding event. Updates the stack.
     * Returns {@code null} if the token is not a value token.
     */
    private Event consumeValueToken(JsonToken t, boolean topLevel) {
        if (t == JsonToken.StartObject.INSTANCE) {
            replaceTopForValueConsumed();
            scopes.push(Scope.OBJECT_START);
            return Event.START_OBJECT;
        }
        if (t == JsonToken.StartArray.INSTANCE) {
            replaceTopForValueConsumed();
            scopes.push(Scope.ARRAY_START);
            return Event.START_ARRAY;
        }
        if (t instanceof JsonToken.StringToken s) {
            lastString = s.value();
            replaceTopForValueConsumed();
            return Event.VALUE_STRING;
        }
        if (t instanceof JsonToken.NumberToken n) {
            lastNumber = n.literal();
            replaceTopForValueConsumed();
            return Event.VALUE_NUMBER;
        }
        if (t == JsonToken.True.INSTANCE) { replaceTopForValueConsumed(); return Event.VALUE_TRUE; }
        if (t == JsonToken.False.INSTANCE) { replaceTopForValueConsumed(); return Event.VALUE_FALSE; }
        if (t == JsonToken.Null.INSTANCE) { replaceTopForValueConsumed(); return Event.VALUE_NULL; }
        return null;
    }

    /** After consuming a primitive value (or opening a container), updates the stack. */
    private void replaceTopForValueConsumed() {
        Scope top = scopes.peek();
        switch (top) {
            case OBJECT_VALUE -> { scopes.pop(); scopes.push(Scope.OBJECT_COMMA); }
            case ARRAY_START, ARRAY_VALUE -> { scopes.pop(); scopes.push(Scope.ARRAY_COMMA); }
            case ROOT -> { /* handled by parseRootValue after return */ }
            default -> {}
        }
    }

    /** After closing a container, returns to the parent scope and switches to COMMA. */
    private void transitionAfterValue() {
        Scope top = scopes.peek();
        switch (top) {
            case OBJECT_VALUE -> { scopes.pop(); scopes.push(Scope.OBJECT_COMMA); }
            case ARRAY_START, ARRAY_VALUE -> { scopes.pop(); scopes.push(Scope.ARRAY_COMMA); }
            case ROOT -> { scopes.pop(); scopes.push(Scope.DONE); }
            default -> {}
        }
    }

    @Override public String getString() {
        if (lastEvent == Event.VALUE_STRING || lastEvent == Event.KEY_NAME) {
            return lastString;
        }
        if (lastEvent == Event.VALUE_NUMBER) {
            return lastNumber;
        }
        throw new IllegalStateException("getString() not valid for event " + lastEvent);
    }

    @Override public boolean isIntegralNumber() {
        requireNumber();
        return !(lastNumber.indexOf('.') >= 0 || lastNumber.indexOf('e') >= 0 || lastNumber.indexOf('E') >= 0);
    }

    @Override public int getInt() {
        requireNumber();
        return getBigDecimal().intValue();
    }

    @Override public long getLong() {
        requireNumber();
        return getBigDecimal().longValue();
    }

    @Override public BigDecimal getBigDecimal() {
        requireNumber();
        return new BigDecimal(lastNumber);
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
            case KEY_NAME, VALUE_STRING -> new ChampollionJsonString(lastString);
            case VALUE_NUMBER -> ChampollionJsonNumber.of(lastNumber);
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
            String key = lastString;
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
            case VALUE_STRING -> new ChampollionJsonString(lastString);
            case VALUE_NUMBER -> ChampollionJsonNumber.of(lastNumber);
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
        scopes.clear();
        scopes.push(Scope.DONE);
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
