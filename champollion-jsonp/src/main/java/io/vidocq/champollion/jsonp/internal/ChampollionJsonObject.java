package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implémentation immuable de {@link JsonObject} adossée à un {@link LinkedHashMap}
 * pour préserver l'ordre d'insertion (spec Jakarta JSON-P §4.1 — implémentations
 * recommandées de préserver l'ordre).
 */
public final class ChampollionJsonObject extends AbstractMap<String, JsonValue> implements JsonObject {

    private final Map<String, JsonValue> members;

    private ChampollionJsonObject(Map<String, JsonValue> members) {
        this.members = members;
    }

    public static ChampollionJsonObject of(Map<String, ? extends JsonValue> source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        var copy = new LinkedHashMap<String, JsonValue>(source.size());
        for (var e : source.entrySet()) {
            if (e.getKey() == null) throw new IllegalArgumentException("null key not allowed");
            if (e.getValue() == null) throw new IllegalArgumentException("null value not allowed (use JsonValue.NULL)");
            copy.put(e.getKey(), e.getValue());
        }
        return new ChampollionJsonObject(Collections.unmodifiableMap(copy));
    }

    @Override public ValueType getValueType() { return ValueType.OBJECT; }

    @Override public Set<Entry<String, JsonValue>> entrySet() { return members.entrySet(); }

    // ===== JsonObject helpers =====

    @Override public JsonArray getJsonArray(String name) { return (JsonArray) members.get(name); }
    @Override public JsonObject getJsonObject(String name) { return (JsonObject) members.get(name); }
    @Override public JsonNumber getJsonNumber(String name) { return (JsonNumber) members.get(name); }
    @Override public JsonString getJsonString(String name) { return (JsonString) members.get(name); }

    @Override public String getString(String name) { return getJsonString(name).getString(); }
    @Override public String getString(String name, String defaultValue) {
        try { return getString(name); } catch (Exception e) { return defaultValue; }
    }

    @Override public int getInt(String name) { return getJsonNumber(name).intValue(); }
    @Override public int getInt(String name, int defaultValue) {
        try { return getInt(name); } catch (Exception e) { return defaultValue; }
    }

    @Override public boolean getBoolean(String name) {
        JsonValue v = members.get(name);
        if (v == JsonValue.TRUE) return true;
        if (v == JsonValue.FALSE) return false;
        throw new ClassCastException("Member " + name + " is not a boolean");
    }

    @Override public boolean getBoolean(String name, boolean defaultValue) {
        try { return getBoolean(name); } catch (Exception e) { return defaultValue; }
    }

    @Override public boolean isNull(String name) {
        return members.get(name) == JsonValue.NULL;
    }

    @Override public String toString() {
        var sb = new StringBuilder().append('{');
        boolean first = true;
        for (var e : members.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonStringEscaper.quote(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
