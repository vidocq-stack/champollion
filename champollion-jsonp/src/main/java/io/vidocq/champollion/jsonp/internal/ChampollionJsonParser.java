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
 * Parser pull-based Jakarta JSON-P 2.1, fondé sur le {@link JsonTokenizer}.
 *
 * <p>Le parser maintient une pile de scopes ({@link Scope}) et une machine d'état
 * minimaliste : à chaque appel à {@link #next()} il consomme un ou plusieurs tokens
 * et émet exactement un {@link Event}.</p>
 *
 * <p>Les méthodes d'accès aux valeurs ({@code getString}, {@code getInt}, etc.) lisent
 * les buffers internes {@code lastString} / {@code lastNumber} positionnés par {@code next()}.</p>
 */
public final class ChampollionJsonParser implements JsonParser {

    private enum Scope { ROOT, OBJECT_START, OBJECT_KEY, OBJECT_COLON, OBJECT_VALUE, OBJECT_COMMA,
                         ARRAY_START, ARRAY_VALUE, ARRAY_COMMA, DONE }

    private final JsonTokenizer tokenizer;
    private final Deque<Scope> scopes = new ArrayDeque<>();

    private Event next;
    private boolean nextReady;
    private String lastString;
    private String lastNumber;
    private Event lastEvent;

    public ChampollionJsonParser(Reader reader) {
        this.tokenizer = new JsonTokenizer(reader);
        this.scopes.push(Scope.ROOT);
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
        // ROOT post-value : on attend EOF ou on lève
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
        // Si la racine n'a pas ouvert de scope, on a fini.
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
     * Consomme un token de valeur et émet l'event correspondant. Met à jour la pile.
     * Retourne {@code null} si le token n'est pas un token de valeur.
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

    /** Après consommation d'une valeur primitive (ou ouverture d'un container), met à jour la pile. */
    private void replaceTopForValueConsumed() {
        Scope top = scopes.peek();
        switch (top) {
            case OBJECT_VALUE -> { scopes.pop(); scopes.push(Scope.OBJECT_COMMA); }
            case ARRAY_START, ARRAY_VALUE -> { scopes.pop(); scopes.push(Scope.ARRAY_COMMA); }
            case ROOT -> { /* géré par parseRootValue après retour */ }
            default -> {}
        }
    }

    /** Après fermeture d'un container, on retombe dans le scope parent et on bascule en COMMA. */
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

    @Override public JsonValue getValue() {
        // Construction de valeur reportée à M2 (object model). Pour l'instant, utilisable
        // uniquement pour les tokens scalaires lus juste avant.
        throw new UnsupportedOperationException("getValue() requires the object model — implemented in M2");
    }

    @Override public JsonObject getObject() {
        throw new UnsupportedOperationException("getObject() requires the object model — implemented in M2");
    }

    @Override public JsonArray getArray() {
        throw new UnsupportedOperationException("getArray() requires the object model — implemented in M2");
    }

    @Override public Stream<JsonValue> getArrayStream() {
        throw new UnsupportedOperationException("getArrayStream() requires the object model — implemented in M2");
    }

    @Override public Stream<java.util.Map.Entry<String, JsonValue>> getObjectStream() {
        throw new UnsupportedOperationException("getObjectStream() requires the object model — implemented in M2");
    }

    @Override public Stream<JsonValue> getValueStream() {
        throw new UnsupportedOperationException("getValueStream() requires the object model — implemented in M2");
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
        // Le Reader sous-jacent est sous la responsabilité de l'appelant de
        // Json.createParser(Reader). Nous fermons quand même via tokenizer si nécessaire.
        scopes.clear();
        scopes.push(Scope.DONE);
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
