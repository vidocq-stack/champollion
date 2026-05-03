package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import java.util.LinkedHashSet;

/**
 * Algorithme RFC 6902 §A.16 : diff entre deux {@link JsonStructure} produit
 * un {@link JsonPatch} qui appliqué à {@code source} donne {@code target}.
 *
 * <p>Approche simple et correcte (pas optimale en taille du patch) :</p>
 * <ul>
 *   <li>Objects : pour chaque clé dans {@code source ∪ target} :
 *     <ul>
 *       <li>Pas dans target → {@code remove}</li>
 *       <li>Pas dans source → {@code add}</li>
 *       <li>Présente dans les deux mais valeurs différentes → recurse si toutes
 *           deux sont des structures, sinon {@code replace}</li>
 *     </ul>
 *   </li>
 *   <li>Arrays : approche naïve par position. Si tailles identiques, recurse
 *       élément par élément. Sinon, replace tout. Optimisations LCS / move
 *       reportées (le résultat reste correct, juste plus verbeux).</li>
 * </ul>
 *
 * <p>Invariant : {@code mergePatch(source, diff(source, target)).equals(target)}
 * si l'on considère l'égalité valeur-par-valeur (cf. {@link ChampollionJsonPatch#equalsByValue}).</p>
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
            // Type différent ou scalaire différent → replace
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
        // Approche simple : tailles différentes → on emet une séquence de remove
        // (depuis la fin) puis add. Sinon recurse position-par-position.
        int sn = source.size();
        int tn = target.size();
        int common = Math.min(sn, tn);
        for (int i = 0; i < common; i++) {
            diffValue(pointer + "/" + i, source.get(i), target.get(i), ops);
        }
        // Suppression des éléments en trop dans source (depuis la fin pour stable indices).
        for (int i = sn - 1; i >= tn; i--) {
            ops.add(removeOp(pointer + "/" + i));
        }
        // Ajout des éléments supplémentaires dans target.
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
