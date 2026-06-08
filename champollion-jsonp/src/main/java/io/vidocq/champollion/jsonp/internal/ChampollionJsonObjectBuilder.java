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
 * Mutable {@link JsonObject} builder. {@link #build()} produces an immutable view.
 * Jakarta JSON-P §4.7. Preserves insertion order.
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
        Objects.requireNonNull(builder, "builder is null");
        builder.build().forEach((k, v) -> members.put(k, v));
        return this;
    }

    @Override public JsonObjectBuilder remove(String name) {
        Objects.requireNonNull(name, "name is null");
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
        // Spec 2.1 §4.7: build() returns the result AND resets the builder for
        // future use.
        var snapshot = new LinkedHashMap<>(members);
        members.clear();
        return ChampollionJsonObject.of(snapshot);
    }
}
