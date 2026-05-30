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
 * JSON-P 2.1 object model writer based on {@link ChampollionJsonGenerator}.
 *
 * <p>Iterative: walks the value via direct emission to the generator. No recursion
 * on document depth (it uses the generator's stack, which is limited by heap
 * memory, not the call stack).</p>
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
        // Spec §3.5: write* does NOT invoke close() — that is close()'s responsibility.
        // We only flush to materialize the content on the Writer side.
        generator.flush();
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
        // Spec §3.5: closes the underlying Writer/OutputStream; propagates
        // IOException as JsonException. If write* was never called, that's fine
        // (no document to materialize).
        try {
            generator.close();
        } catch (jakarta.json.stream.JsonGenerationException e) {
            // Incomplete document (close without write*): ignored to allow clean
            // closing in a finally / try-with-resources block.
        }
    }
}
