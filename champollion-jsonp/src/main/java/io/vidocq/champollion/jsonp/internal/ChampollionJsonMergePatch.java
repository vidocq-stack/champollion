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

import jakarta.json.JsonMergePatch;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable {@link JsonMergePatch} implementation conforming to RFC 7396.
 *
 * <p>Algorithme RFC 7396 §1 :
 * <pre>
 *   define MergePatch(Target, Patch):
 *     if Patch is an Object:
 *       if Target is not an Object:
 *         Target = {} // Ignore the contents and set it to an empty Object
 *       for each Name/Value pair in Patch:
 *         if Value is null:
 *           if Name exists in Target:
 *             remove the Name/Value pair from Target
 *         else:
 *           Target[Name] = MergePatch(Target[Name], Value)
 *       return Target
 *     else:
 *       return Patch
 * </pre>
 */
public final class ChampollionJsonMergePatch implements JsonMergePatch {

    private final JsonValue patch;

    public ChampollionJsonMergePatch(JsonValue patch) {
        this.patch = Objects.requireNonNull(patch, "patch is null");
    }

    @Override public JsonValue apply(JsonValue target) {
        return mergePatch(target, patch);
    }

    @Override public JsonValue toJsonValue() { return patch; }

    private static JsonValue mergePatch(JsonValue target, JsonValue patch) {
        if (!(patch instanceof JsonObject patchObj)) {
            return patch;
        }
        // patch is an object
        Map<String, JsonValue> base = (target instanceof JsonObject t)
                ? new LinkedHashMap<>(t)
                : new LinkedHashMap<>();
        for (var e : patchObj.entrySet()) {
            String name = e.getKey();
            JsonValue v = e.getValue();
            if (v == JsonValue.NULL) {
                base.remove(name);
            } else {
                JsonValue currentChild = base.getOrDefault(name, JsonValue.NULL);
                base.put(name, mergePatch(currentChild, v));
            }
        }
        return ChampollionJsonObject.of(base);
    }

    /**
     * Computes a merge patch such that {@code mergePatch(source, diff(source, target)) == target}.
     * RFC 7396 §1 note: if source/target are not both objects, the patch is target.
     */
    public static JsonMergePatch diff(JsonValue source, JsonValue target) {
        return new ChampollionJsonMergePatch(diffValue(source, target));
    }

    private static JsonValue diffValue(JsonValue source, JsonValue target) {
        if (!(source instanceof JsonObject src) || !(target instanceof JsonObject tgt)) {
            return target;
        }
        var patch = new LinkedHashMap<String, JsonValue>();
        // Members in target: add or modify
        for (var e : tgt.entrySet()) {
            JsonValue srcVal = src.get(e.getKey());
            if (srcVal == null) {
                patch.put(e.getKey(), e.getValue());
            } else if (!ChampollionJsonPatch.equalsByValue(srcVal, e.getValue())) {
                patch.put(e.getKey(), diffValue(srcVal, e.getValue()));
            }
        }
        // Members only in source: null to delete
        for (var name : src.keySet()) {
            if (!tgt.containsKey(name)) {
                patch.put(name, JsonValue.NULL);
            }
        }
        return ChampollionJsonObject.of(patch);
    }
}
