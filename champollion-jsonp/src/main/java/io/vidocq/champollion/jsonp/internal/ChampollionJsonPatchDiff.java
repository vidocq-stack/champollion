package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import java.util.LinkedHashSet;

/**
 * RFC 6902 §A.16 algorithm: diffing two {@link JsonStructure}s produces a
 * {@link JsonPatch} that, when applied to {@code source}, yields {@code target}.
 *
 * <p>Simple, correct approach (not optimal in patch size):</p>
 * <ul>
 *   <li>Objects: for each key in {@code source ∪ target}:
 *     <ul>
 *       <li>Missing from target → {@code remove}</li>
 *       <li>Missing from source → {@code add}</li>
 *       <li>Present in both but different values → recurse if both are
 *           structures, otherwise {@code replace}</li>
 *     </ul>
 *   </li>
 *   <li>Arrays: naive position-based approach. If sizes match, recurse element by
 *       element. Otherwise, replace everything. LCS / move optimizations are
 *       deferred (the result remains correct, just more verbose).</li>
 * </ul>
 *
 * <p>Invariant: {@code mergePatch(source, diff(source, target)).equals(target)}
 * if value-by-value equality is considered (see {@link ChampollionJsonPatch#equalsByValue}).</p>
 */
final class ChampollionJsonPatchDiff {

    private ChampollionJsonPatchDiff() {}

    static JsonPatch diff(JsonStructure source, JsonStructure target) {
        var ops = new ChampollionJsonArrayBuilder();
        diffValue("", source, target, ops);
        return new ChampollionJsonPatch(ops.build());
    }

    private static void diffValue(String pointer, JsonValue source, JsonValue target,
                                  ChampollionJsonArrayBuilder ops) {
        if (ChampollionJsonPatch.equalsByValue(source, target)) return;

        if (source instanceof JsonObject so && target instanceof JsonObject to) {
            diffObject(pointer, so, to, ops);
        } else if (source instanceof JsonArray sa && target instanceof JsonArray ta) {
            diffArray(pointer, sa, ta, ops);
        } else {
            // Different type or different scalar → replace
            ops.add(replaceOp(pointer, target));
        }
    }

    private static void diffObject(String pointer, JsonObject source, JsonObject target,
                                   ChampollionJsonArrayBuilder ops) {
        var keys = new LinkedHashSet<String>(source.keySet());
        keys.addAll(target.keySet());
        for (String k : keys) {
            String childPointer = pointer + "/" + escape(k);
            boolean inS = source.containsKey(k);
            boolean inT = target.containsKey(k);
            if (inS && !inT) {
                ops.add(removeOp(childPointer));
            } else if (!inS && inT) {
                ops.add(addOp(childPointer, target.get(k)));
            } else {
                diffValue(childPointer, source.get(k), target.get(k), ops);
            }
        }
    }

    private static void diffArray(String pointer, JsonArray source, JsonArray target,
                                  ChampollionJsonArrayBuilder ops) {
        // Simple approach: different sizes → emit a sequence of remove operations
        // (from the end) then add. Otherwise recurse position by position.
        int sn = source.size();
        int tn = target.size();
        int common = Math.min(sn, tn);
        for (int i = 0; i < common; i++) {
            diffValue(pointer + "/" + i, source.get(i), target.get(i), ops);
        }
        // Remove extra elements in source (from the end for stable indices).
        for (int i = sn - 1; i >= tn; i--) {
            ops.add(removeOp(pointer + "/" + i));
        }
        // Add extra elements in target.
        for (int i = sn; i < tn; i++) {
            ops.add(addOp(pointer + "/" + i, target.get(i)));
        }
    }

    private static JsonObject addOp(String path, JsonValue value) {
        return new ChampollionJsonObjectBuilder()
                .add("op", "add")
                .add("path", path)
                .add("value", value)
                .build();
    }

    private static JsonObject removeOp(String path) {
        return new ChampollionJsonObjectBuilder()
                .add("op", "remove")
                .add("path", path)
                .build();
    }

    private static JsonObject replaceOp(String path, JsonValue value) {
        return new ChampollionJsonObjectBuilder()
                .add("op", "replace")
                .add("path", path)
                .add("value", value)
                .build();
    }

    /** Escape RFC 6901 inverse : {@code ~} → {@code ~0}, {@code /} → {@code ~1}. */
    private static String escape(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }
}
