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
 * Lecteur d'object model JSON-P 2.1 fondé sur {@link ChampollionJsonParser}.
 *
 * <p>Construction itérative à pile pour éviter une récursion proportionnelle à
 * la profondeur du document — virtual-thread-friendly et resistant aux JSON très
 * profonds (cf. corpus JSONTestSuite {@code i_structure_500_nested_arrays.json}).</p>
 */
public final class ChampollionJsonReader implements JsonReader {

    private final JsonParser parser;
    private boolean consumed;

    public ChampollionJsonReader(Reader reader) {
        this.parser = new ChampollionJsonParser(reader);
    }

    public ChampollionJsonReader(JsonParser parser) {
        this.parser = parser;
    }

    @Override public JsonStructure read() {
        JsonValue v = readValue();
        if (!(v instanceof JsonStructure s)) {
            throw new JsonException("Root JSON value is not an object or array");
        }
        return s;
    }

    @Override public JsonObject readObject() { return (JsonObject) read(); }
    @Override public JsonArray readArray() { return (JsonArray) read(); }

    @Override public JsonValue readValue() {
        if (consumed) throw new IllegalStateException("read* methods cannot be invoked twice");
        consumed = true;

        if (!parser.hasNext()) throw new JsonException("Empty input");
        JsonParser.Event e = parser.next();
        JsonValue root = parseValue(e);

        if (parser.hasNext()) {
            // Le parser refuse déjà les multi-roots, mais on garde une garde de sécurité.
            throw new JsonException("Unexpected trailing content after root value");
        }
        return root;
    }

    private JsonValue parseValue(JsonParser.Event first) {
        // Pile de "frames" : chaque frame est soit un builder objet (avec le nom courant)
        // soit un builder array. Construction iterative.
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

            // Si l'événement courant a produit une valeur (END_* ou VALUE_*), l'attacher au parent.
            if (currentValue != null) {
                if (stack.isEmpty()) return currentValue;
                Frame parent = stack.peek();
                if (parent instanceof ObjectFrame of) {
                    of.members.put(of.pendingKey, currentValue);
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

    @Override public void close() { parser.close(); }

    private sealed interface Frame permits ObjectFrame, ArrayFrame {}

    private static final class ObjectFrame implements Frame {
        final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        String pendingKey;
    }

    private static final class ArrayFrame implements Frame {
        final java.util.ArrayList<JsonValue> values = new java.util.ArrayList<>();
    }
}
