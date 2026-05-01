package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonPatch;
import jakarta.json.JsonPatchBuilder;
import jakarta.json.JsonValue;

/**
 * Builder mutable construisant un tableau JSON Patch (RFC 6902) op par op.
 */
public final class ChampollionJsonPatchBuilder implements JsonPatchBuilder {

    private final ChampollionJsonArrayBuilder ops = new ChampollionJsonArrayBuilder();

    public ChampollionJsonPatchBuilder() {}

    public ChampollionJsonPatchBuilder(JsonArray initial) {
        if (initial != null) {
            for (JsonValue v : initial) ops.add(v);
        }
    }

    @Override public JsonPatchBuilder add(String path, JsonValue value) { return op("add", path, "value", value); }
    @Override public JsonPatchBuilder add(String path, String value) { return add(path, new ChampollionJsonString(value)); }
    @Override public JsonPatchBuilder add(String path, int value) { return add(path, ChampollionJsonNumber.of(value)); }
    @Override public JsonPatchBuilder add(String path, boolean value) { return add(path, value ? JsonValue.TRUE : JsonValue.FALSE); }

    @Override public JsonPatchBuilder remove(String path) {
        ops.add(new ChampollionJsonObjectBuilder()
                .add("op", "remove")
                .add("path", path)
                .build());
        return this;
    }

    @Override public JsonPatchBuilder replace(String path, JsonValue value) { return op("replace", path, "value", value); }
    @Override public JsonPatchBuilder replace(String path, String value) { return replace(path, new ChampollionJsonString(value)); }
    @Override public JsonPatchBuilder replace(String path, int value) { return replace(path, ChampollionJsonNumber.of(value)); }
    @Override public JsonPatchBuilder replace(String path, boolean value) { return replace(path, value ? JsonValue.TRUE : JsonValue.FALSE); }

    @Override public JsonPatchBuilder move(String path, String from) {
        ops.add(new ChampollionJsonObjectBuilder()
                .add("op", "move")
                .add("from", from)
                .add("path", path)
                .build());
        return this;
    }

    @Override public JsonPatchBuilder copy(String path, String from) {
        ops.add(new ChampollionJsonObjectBuilder()
                .add("op", "copy")
                .add("from", from)
                .add("path", path)
                .build());
        return this;
    }

    @Override public JsonPatchBuilder test(String path, JsonValue value) { return op("test", path, "value", value); }
    @Override public JsonPatchBuilder test(String path, String value) { return test(path, new ChampollionJsonString(value)); }
    @Override public JsonPatchBuilder test(String path, int value) { return test(path, ChampollionJsonNumber.of(value)); }
    @Override public JsonPatchBuilder test(String path, boolean value) { return test(path, value ? JsonValue.TRUE : JsonValue.FALSE); }

    private JsonPatchBuilder op(String name, String path, String valueKey, JsonValue value) {
        ops.add(new ChampollionJsonObjectBuilder()
                .add("op", name)
                .add("path", path)
                .add(valueKey, value)
                .build());
        return this;
    }

    @Override public JsonPatch build() {
        return new ChampollionJsonPatch(ops.build());
    }
}
