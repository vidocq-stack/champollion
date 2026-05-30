package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Mutable {@link JsonArray} builder. {@link #build()} produces an immutable view.
 * Jakarta JSON-P §4.8.
 */
public final class ChampollionJsonArrayBuilder implements JsonArrayBuilder {

    private final List<JsonValue> values = new ArrayList<>();

    @Override public JsonArrayBuilder add(JsonValue value) {
        return push(value);
    }

    @Override public JsonArrayBuilder add(String value) {
        return push(new ChampollionJsonString(value));
    }

    @Override public JsonArrayBuilder add(BigDecimal value) {
        return push(ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(BigInteger value) {
        return push(ChampollionJsonNumber.of(new BigDecimal(value)));
    }

    @Override public JsonArrayBuilder add(int value) {
        return push(ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(long value) {
        return push(ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        return push(ChampollionJsonNumber.of(BigDecimal.valueOf(value)));
    }

    @Override public JsonArrayBuilder add(boolean value) {
        return push(value ? JsonValue.TRUE : JsonValue.FALSE);
    }

    @Override public JsonArrayBuilder addNull() {
        return push(JsonValue.NULL);
    }

    @Override public JsonArrayBuilder add(JsonObjectBuilder builder) {
        return push(builder.build());
    }

    @Override public JsonArrayBuilder add(JsonArrayBuilder builder) {
        return push(builder.build());
    }

    @Override public JsonArrayBuilder addAll(JsonArrayBuilder builder) {
        values.addAll(builder.build());
        return this;
    }

    @Override public JsonArrayBuilder add(int index, JsonValue value) {
        values.add(index, Objects.requireNonNull(value));
        return this;
    }

    @Override public JsonArrayBuilder add(int index, String value) {
        return add(index, (JsonValue) new ChampollionJsonString(value));
    }

    @Override public JsonArrayBuilder add(int index, BigDecimal value) {
        return add(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(int index, BigInteger value) {
        return add(index, (JsonValue) ChampollionJsonNumber.of(new BigDecimal(value)));
    }

    @Override public JsonArrayBuilder add(int index, int value) {
        return add(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(int index, long value) {
        return add(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder add(int index, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        return add(index, (JsonValue) ChampollionJsonNumber.of(BigDecimal.valueOf(value)));
    }

    @Override public JsonArrayBuilder add(int index, boolean value) {
        return add(index, (JsonValue) (value ? JsonValue.TRUE : JsonValue.FALSE));
    }

    @Override public JsonArrayBuilder addNull(int index) {
        return add(index, (JsonValue) JsonValue.NULL);
    }

    @Override public JsonArrayBuilder add(int index, JsonObjectBuilder builder) {
        return add(index, (JsonValue) builder.build());
    }

    @Override public JsonArrayBuilder add(int index, JsonArrayBuilder builder) {
        return add(index, (JsonValue) builder.build());
    }

    @Override public JsonArrayBuilder set(int index, JsonValue value) {
        values.set(index, Objects.requireNonNull(value));
        return this;
    }

    @Override public JsonArrayBuilder set(int index, String value) {
        return set(index, (JsonValue) new ChampollionJsonString(value));
    }

    @Override public JsonArrayBuilder set(int index, BigDecimal value) {
        return set(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder set(int index, BigInteger value) {
        return set(index, (JsonValue) ChampollionJsonNumber.of(new BigDecimal(value)));
    }

    @Override public JsonArrayBuilder set(int index, int value) {
        return set(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder set(int index, long value) {
        return set(index, (JsonValue) ChampollionJsonNumber.of(value));
    }

    @Override public JsonArrayBuilder set(int index, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        return set(index, (JsonValue) ChampollionJsonNumber.of(BigDecimal.valueOf(value)));
    }

    @Override public JsonArrayBuilder set(int index, boolean value) {
        return set(index, (JsonValue) (value ? JsonValue.TRUE : JsonValue.FALSE));
    }

    @Override public JsonArrayBuilder setNull(int index) {
        return set(index, (JsonValue) JsonValue.NULL);
    }

    @Override public JsonArrayBuilder set(int index, JsonObjectBuilder builder) {
        return set(index, (JsonValue) builder.build());
    }

    @Override public JsonArrayBuilder set(int index, JsonArrayBuilder builder) {
        return set(index, (JsonValue) builder.build());
    }

    @Override public JsonArrayBuilder remove(int index) {
        values.remove(index);
        return this;
    }

    private JsonArrayBuilder push(JsonValue value) {
        values.add(Objects.requireNonNull(value));
        return this;
    }

    @Override public JsonArray build() {
        // Spec 2.1 §4.8: build() returns the result AND resets the builder.
        var snapshot = List.copyOf(values);
        values.clear();
        return ChampollionJsonArray.of(snapshot);
    }

    /** Internal convenience for Collection<JsonValue> conversions. */
    public ChampollionJsonArrayBuilder addAll(Collection<? extends JsonValue> all) {
        values.addAll(all);
        return this;
    }
}
