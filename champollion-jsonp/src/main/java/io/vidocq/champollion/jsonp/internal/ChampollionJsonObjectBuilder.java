package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder mutable de {@link JsonObject}. {@link #build()} produit une vue immuable.
 * Spec Jakarta JSON-P §4.7. Préserve l'ordre d'insertion.
 */
public final class ChampollionJsonObjectBuilder implements JsonObjectBuilder {

    private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

    @Override public JsonObjectBuilder add(String name, JsonValue value) {
        return put(name, value);
    }

    @Override public JsonObjectBuilder add(String name, String value) {
        return put(name, new ChampollionJsonString(value));
    }

    @Override public JsonObjectBuilder add(String name, BigInteger value) {
        return put(name, ChampollionJsonNumber.of(new BigDecimal(value)));
    }

    @Override public JsonObjectBuilder add(String name, BigDecimal value) {
        return put(name, ChampollionJsonNumber.of(value));
    }

    @Override public JsonObjectBuilder add(String name, int value) {
        return put(name, ChampollionJsonNumber.of(value));
    }

    @Override public JsonObjectBuilder add(String name, long value) {
        return put(name, ChampollionJsonNumber.of(value));
    }

    @Override public JsonObjectBuilder add(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        return put(name, ChampollionJsonNumber.of(BigDecimal.valueOf(value)));
    }

    @Override public JsonObjectBuilder add(String name, boolean value) {
        return put(name, value ? JsonValue.TRUE : JsonValue.FALSE);
    }

    @Override public JsonObjectBuilder addNull(String name) {
        return put(name, JsonValue.NULL);
    }

    @Override public JsonObjectBuilder add(String name, JsonObjectBuilder builder) {
        return put(name, builder.build());
    }

    @Override public JsonObjectBuilder add(String name, JsonArrayBuilder builder) {
        return put(name, builder.build());
    }

    @Override public JsonObjectBuilder addAll(JsonObjectBuilder builder) {
        builder.build().forEach((k, v) -> members.put(k, v));
        return this;
    }

    @Override public JsonObjectBuilder remove(String name) {
        members.remove(name);
        return this;
    }

    private JsonObjectBuilder put(String name, JsonValue value) {
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(value, "value is null (use addNull or JsonValue.NULL)");
        members.put(name, value);
        return this;
    }

    @Override public JsonObject build() {
        // Spec 2.1 §4.7 : build() retourne le résultat ET réinitialise le builder
        // pour les utilisations futures.
        var snapshot = new LinkedHashMap<>(members);
        members.clear();
        return ChampollionJsonObject.of(snapshot);
    }
}
