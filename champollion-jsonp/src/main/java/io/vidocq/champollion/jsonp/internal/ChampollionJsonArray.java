/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.AbstractList;
import java.util.List;

/**
 * Immutable {@link JsonArray} implementation. Delegates to an unmodifiable
 * {@code List<JsonValue>}. Jakarta JSON-P §4.2.
 */
public final class ChampollionJsonArray extends AbstractList<JsonValue> implements JsonArray {

    private final List<JsonValue> values;

    private ChampollionJsonArray(List<JsonValue> values) {
        this.values = values;
    }

    public static ChampollionJsonArray of(List<? extends JsonValue> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return new ChampollionJsonArray(List.copyOf(values));
    }

    @Override public ValueType getValueType() { return ValueType.ARRAY; }

    @Override public JsonValue get(int index) { return values.get(index); }
    @Override public int size() { return values.size(); }

    // ===== JsonArray helpers =====

    @Override public JsonObject getJsonObject(int index) { return (JsonObject) values.get(index); }
    @Override public JsonArray getJsonArray(int index) { return (JsonArray) values.get(index); }
    @Override public JsonNumber getJsonNumber(int index) { return (JsonNumber) values.get(index); }
    @Override public JsonString getJsonString(int index) { return (JsonString) values.get(index); }

    @Override public String getString(int index) { return getJsonString(index).getString(); }

    @Override public String getString(int index, String defaultValue) {
        try { return getString(index); } catch (Exception e) { return defaultValue; }
    }

    @Override public int getInt(int index) { return getJsonNumber(index).intValue(); }

    @Override public int getInt(int index, int defaultValue) {
        try { return getInt(index); } catch (Exception e) { return defaultValue; }
    }

    @Override public boolean getBoolean(int index) {
        JsonValue v = values.get(index);
        if (v == JsonValue.TRUE) return true;
        if (v == JsonValue.FALSE) return false;
        throw new ClassCastException("Element at index " + index + " is not a boolean");
    }

    @Override public boolean getBoolean(int index, boolean defaultValue) {
        try { return getBoolean(index); } catch (Exception e) { return defaultValue; }
    }

    @Override public boolean isNull(int index) {
        return values.get(index) == JsonValue.NULL;
    }

    @Override
    public <T extends JsonValue> List<T> getValuesAs(Class<T> clazz) {
        @SuppressWarnings("unchecked")
        List<T> typed = (List<T>) values;
        return typed;
    }

    @Override public String toString() {
        var sb = new StringBuilder().append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }
}
