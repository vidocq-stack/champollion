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
    // Capacité initiale 4 — la profondeur de nesting moyenne d'un JSON de
    // production reste largement sous 8 ; le défaut ArrayDeque (16) alloue un
    // backing array inutilement grand sur le hot path read.
    private final Deque<Scope> scopes = new ArrayDeque<>(4);

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

    @Override public Event currentEvent() {
        return lastEvent;
    }

    @Override public JsonValue getValue() {
        // Spec §3.10 (2.1) : retourne la valeur à la position courante. Pour
        // START_OBJECT/START_ARRAY, équivaut à getObject()/getArray(). Pour KEY_NAME,
        // retourne la string du nom. Pour VALUE_*, le JsonValue scalaire correspondant.
        // Si aucun event n'a encore été lu, on avance automatiquement (cas TCK 2.1).
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
        // §3.10.2.1 — accepter aussi un parser fresh (pas encore next-é) : on
        // pre-next pour atteindre START_OBJECT (compat TCK Jersey + JsonbDeserializer
        // customs qui appellent getObject() directement).
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

    /** Lit les members jusqu'à END_OBJECT (le START_OBJECT initial est déjà consommé). */
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

    /** Lit les éléments jusqu'à END_ARRAY (le START_ARRAY initial est déjà consommé). */
    private JsonArray readArrayElements() {
        var list = new java.util.ArrayList<JsonValue>();
        while (true) {
            Event e = next();
            if (e == Event.END_ARRAY) return ChampollionJsonArray.of(list);
            list.add(readScalarOrStructure(e));
        }
    }

    /** Construit un JsonValue à partir de l'event courant (déjà lu). */
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
        // Implémentation simple : lit tout l'array en mémoire puis stream dessus.
        return readArrayElements().stream().map(v -> v);
    }

    @Override public Stream<java.util.Map.Entry<String, JsonValue>> getObjectStream() {
        if (lastEvent != Event.START_OBJECT) {
            throw new IllegalStateException("getObjectStream() requires last event = START_OBJECT, got " + lastEvent);
        }
        return readObjectMembers().entrySet().stream();
    }

    @Override public Stream<JsonValue> getValueStream() {
        // Spec §3.10 : getValueStream() doit être appelé au niveau racine du
        // document avant tout next(). Si on est dans un object/array, throw
        // IllegalStateException.
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
        // Spec §3.6 : ferme le Reader/InputStream sous-jacent ; propage IOException
        // en JsonException.
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
