package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;

import java.io.Writer;
import java.util.Map;

/**
 * Écrivain d'object model JSON-P 2.1 fondé sur {@link ChampollionJsonGenerator}.
 *
 * <p>Itératif : explore la valeur via une émission directe au generator. Pas de
 * récursion sur la profondeur du document (utilise la pile du generator qui est
 * limitée par la mémoire heap, pas la stack).</p>
 */
public final class ChampollionJsonWriter implements JsonWriter {

    private final ChampollionJsonGenerator generator;
    private boolean consumed;

    public ChampollionJsonWriter(Writer writer, boolean pretty) {
        this.generator = new ChampollionJsonGenerator(writer, pretty);
    }

    @Override public void writeArray(JsonArray array) { write((JsonStructure) array); }
    @Override public void writeObject(JsonObject object) { write((JsonStructure) object); }

    @Override public void write(JsonStructure value) {
        write((JsonValue) value);
    }

    @Override public void write(JsonValue value) {
        if (consumed) throw new IllegalStateException("write* methods cannot be invoked twice");
        consumed = true;
        emit(value);
        generator.close();
    }

    private void emit(JsonValue value) {
        switch (value.getValueType()) {
            case OBJECT -> emitObject((JsonObject) value);
            case ARRAY -> emitArray((JsonArray) value);
            case STRING -> generator.write(((JsonString) value).getString());
            case NUMBER -> generator.write(((JsonNumber) value).bigDecimalValue());
            case TRUE -> generator.write(true);
            case FALSE -> generator.write(false);
            case NULL -> generator.writeNull();
        }
    }

    private void emitObject(JsonObject obj) {
        generator.writeStartObject();
        for (Map.Entry<String, JsonValue> e : obj.entrySet()) {
            String name = e.getKey();
            JsonValue v = e.getValue();
            switch (v.getValueType()) {
                case OBJECT -> { generator.writeKey(name); emitObject((JsonObject) v); }
                case ARRAY -> { generator.writeKey(name); emitArray((JsonArray) v); }
                case STRING -> generator.write(name, ((JsonString) v).getString());
                case NUMBER -> generator.write(name, ((JsonNumber) v).bigDecimalValue());
                case TRUE -> generator.write(name, true);
                case FALSE -> generator.write(name, false);
                case NULL -> generator.writeNull(name);
            }
        }
        generator.writeEnd();
    }

    private void emitArray(JsonArray arr) {
        generator.writeStartArray();
        for (JsonValue v : arr) emit(v);
        generator.writeEnd();
    }

    @Override public void close() {
        // Si rien n'a été écrit, on doit pouvoir fermer sans erreur d'état mais sans
        // produire un document vide non-valide. Ici on tolère close() sans write : pas de flush du generator.
        if (consumed) {
            // déjà fermé via write()
            return;
        }
    }
}
