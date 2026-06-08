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
