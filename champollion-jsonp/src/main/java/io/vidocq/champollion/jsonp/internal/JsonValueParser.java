package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Parser that iterates over an in-memory {@link jakarta.json.JsonStructure} or
 * {@link JsonValue} and emits the corresponding {@link JsonParser.Event}s, as if
 * the structure had been read from a Reader.
 *
 * <p>Spec §3.4: used by {@code Json.createParserFactory().createParser(JsonObject)}
 * and {@code .createParser(JsonArray)}.</p>
 */
public final class JsonValueParser implements JsonParser {

    private final Deque<Frame> stack = new ArrayDeque<>();
    private Event lastEvent;
    private String lastString;
    private JsonNumber lastNumber;

    public JsonValueParser(JsonValue root) {
        if (root == null) throw new IllegalArgumentException("root is null");
        if (root instanceof JsonObject o) stack.push(new ObjectFrame(o, false));
        else if (root instanceof JsonArray a) stack.push(new ArrayFrame(a, false));
        else stack.push(new ScalarFrame(root));
    }

    @Override public boolean hasNext() { return !stack.isEmpty(); }

    @Override public Event next() {
        if (stack.isEmpty()) throw new NoSuchElementException();
        Frame top = stack.peek();
        return switch (top) {
            case ObjectFrame of -> nextObject(of);
            case ArrayFrame af -> nextArray(af);
            case ScalarFrame sf -> nextScalar(sf);
            case ValueFrame vf -> nextValue(vf);
        };
    }

    private Event nextObject(ObjectFrame of) {
        if (!of.entered) {
            of.entered = true;
            return setEvent(Event.START_OBJECT);
        }
        if (of.pendingValue != null) {
            JsonValue v = of.pendingValue;
            of.pendingValue = null;
            return enterValue(v);
        }
        if (of.iter.hasNext()) {
            var e = of.iter.next();
            lastString = e.getKey();
            of.pendingValue = e.getValue();
            return setEvent(Event.KEY_NAME);
        }
        stack.pop();
        return setEvent(Event.END_OBJECT);
    }

    private Event nextArray(ArrayFrame af) {
        if (!af.entered) {
            af.entered = true;
            return setEvent(Event.START_ARRAY);
        }
        if (af.iter.hasNext()) {
            JsonValue v = af.iter.next();
            return enterValue(v);
        }
        stack.pop();
        return setEvent(Event.END_ARRAY);
    }

    private Event nextScalar(ScalarFrame sf) {
        stack.pop();
        return scalarEvent(sf.value);
    }

    private Event nextValue(ValueFrame vf) {
        stack.pop();
        return scalarEvent(vf.value);
    }

    /** Pushes the value onto the stack and emits the correct start event. */
    private Event enterValue(JsonValue v) {
        if (v instanceof JsonObject o) {
            stack.push(new ObjectFrame(o, true));
            return setEvent(Event.START_OBJECT);
        }
        if (v instanceof JsonArray a) {
            stack.push(new ArrayFrame(a, true));
            return setEvent(Event.START_ARRAY);
        }
        return scalarEvent(v);
    }

    private Event scalarEvent(JsonValue v) {
        return switch (v.getValueType()) {
            case STRING -> {
                lastString = ((JsonString) v).getString();
                yield setEvent(Event.VALUE_STRING);
            }
            case NUMBER -> {
                lastNumber = (JsonNumber) v;
                yield setEvent(Event.VALUE_NUMBER);
            }
            case TRUE -> setEvent(Event.VALUE_TRUE);
            case FALSE -> setEvent(Event.VALUE_FALSE);
            case NULL -> setEvent(Event.VALUE_NULL);
            default -> throw new IllegalStateException("Unexpected scalar: " + v.getValueType());
        };
    }

    private Event setEvent(Event e) { this.lastEvent = e; return e; }

    @Override public String getString() {
        if (lastEvent == Event.KEY_NAME || lastEvent == Event.VALUE_STRING) return lastString;
        if (lastEvent == Event.VALUE_NUMBER) return lastNumber.toString();
        throw new IllegalStateException("getString() not valid for " + lastEvent);
    }
    @Override public boolean isIntegralNumber() { requireNumber(); return lastNumber.isIntegral(); }
    @Override public int getInt() { requireNumber(); return lastNumber.intValue(); }
    @Override public long getLong() { requireNumber(); return lastNumber.longValue(); }
    @Override public BigDecimal getBigDecimal() { requireNumber(); return lastNumber.bigDecimalValue(); }
    @Override public JsonLocation getLocation() {
        return new SimpleLocation(0, 0, 0);
    }

    @Override public Event currentEvent() { return lastEvent; }

    @Override public JsonValue getValue() {
        if (lastEvent == null) {
            if (!hasNext()) throw new IllegalStateException();
            next();
        }
        // For scalar events, return the corresponding JsonValue.
        return switch (lastEvent) {
            case VALUE_STRING -> new ChampollionJsonString(lastString);
            case VALUE_NUMBER -> lastNumber;
            case VALUE_TRUE -> JsonValue.TRUE;
            case VALUE_FALSE -> JsonValue.FALSE;
            case VALUE_NULL -> JsonValue.NULL;
            case KEY_NAME -> new ChampollionJsonString(lastString);
            case START_OBJECT, START_ARRAY -> {
                // The current frame has been entered; return its complete structure.
                // Strategy: extract the JsonValue from the top frame (before pop).
                Frame top = stack.peek();
                yield switch (top) {
                    case ObjectFrame of -> { stack.pop(); skipToEnd(of); yield of.source; }
                    case ArrayFrame af -> { stack.pop(); skipToEnd(af); yield af.source; }
                    default -> throw new IllegalStateException();
                };
            }
            default -> throw new IllegalStateException("getValue() not valid for " + lastEvent);
        };
    }

    private void skipToEnd(Frame f) {
        // The current frame has been popped; advance state so it stays consistent
        // with leaving the container (we "consume" the remaining elements).
        // Since the internal state does not depend on it, marking END is enough.
        if (f instanceof ObjectFrame) lastEvent = Event.END_OBJECT;
        else lastEvent = Event.END_ARRAY;
    }

    @Override public JsonObject getObject() {
        if (lastEvent != Event.START_OBJECT) {
            throw new IllegalStateException("getObject() requires last event = START_OBJECT");
        }
        Frame top = stack.peek();
        if (!(top instanceof ObjectFrame of)) throw new IllegalStateException();
        stack.pop();
        skipToEnd(of);
        return of.source;
    }

    @Override public JsonArray getArray() {
        if (lastEvent != Event.START_ARRAY) {
            throw new IllegalStateException("getArray() requires last event = START_ARRAY");
        }
        Frame top = stack.peek();
        if (!(top instanceof ArrayFrame af)) throw new IllegalStateException();
        stack.pop();
        skipToEnd(af);
        return af.source;
    }

    @Override public Stream<JsonValue> getArrayStream() { return getArray().stream(); }
    @Override public Stream<Map.Entry<String, JsonValue>> getObjectStream() { return getObject().entrySet().stream(); }
    @Override public Stream<JsonValue> getValueStream() { return Stream.of(getValue()); }

    @Override public void skipArray() {
        if (lastEvent == Event.START_ARRAY) {
            getArray();
        }
    }
    @Override public void skipObject() {
        if (lastEvent == Event.START_OBJECT) {
            getObject();
        }
    }

    @Override public void close() { stack.clear(); }

    private void requireNumber() {
        if (lastEvent != Event.VALUE_NUMBER) {
            throw new IllegalStateException("number accessor not valid for " + lastEvent);
        }
    }

    // ===== Frames =====

    private sealed interface Frame permits ObjectFrame, ArrayFrame, ScalarFrame, ValueFrame {}

    private static final class ObjectFrame implements Frame {
        final JsonObject source;
        final Iterator<Map.Entry<String, JsonValue>> iter;
        boolean entered;
        JsonValue pendingValue;
        ObjectFrame(JsonObject source, boolean entered) {
            this.source = source;
            this.iter = source.entrySet().iterator();
            this.entered = entered;
        }
    }

    private static final class ArrayFrame implements Frame {
        final JsonArray source;
        final Iterator<JsonValue> iter;
        boolean entered;
        ArrayFrame(JsonArray source, boolean entered) {
            this.source = source;
            this.iter = source.iterator();
            this.entered = entered;
        }
    }

    private static final class ScalarFrame implements Frame {
        final JsonValue value;
        ScalarFrame(JsonValue value) { this.value = value; }
    }

    private static final class ValueFrame implements Frame {
        final JsonValue value;
        ValueFrame(JsonValue value) { this.value = value; }
    }

    private record SimpleLocation(long lineNumber, long columnNumber, long streamOffset) implements JsonLocation {
        @Override public long getLineNumber() { return lineNumber; }
        @Override public long getColumnNumber() { return columnNumber; }
        @Override public long getStreamOffset() { return streamOffset; }
    }
}
