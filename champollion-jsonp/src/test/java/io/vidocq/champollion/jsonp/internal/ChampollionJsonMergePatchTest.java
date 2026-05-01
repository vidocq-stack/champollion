package io.vidocq.champollion.jsonp.internal;

import jakarta.json.Json;
import jakarta.json.JsonMergePatch;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ChampollionJsonMergePatch — RFC 7396")
class ChampollionJsonMergePatchTest {

    private static JsonValue read(String s) {
        try (var r = Json.createReader(new StringReader(s))) {
            return r.readValue();
        }
    }

    private static JsonValue apply(String target, String patch) {
        var p = new ChampollionJsonMergePatch(read(patch));
        return p.apply(read(target));
    }

    /** RFC 7396 §3 : valeur scalaire dans le patch remplace tout. */
    @Test
    void scalar_patch_replaces_target() {
        assertEquals(read("\"new\""), apply("{\"a\":1}", "\"new\""));
        assertEquals(read("42"), apply("[1,2,3]", "42"));
    }

    /** RFC 7396 §1 : object patch fusionne member par member. */
    @Test
    void merges_object_members() {
        assertEquals(read("{\"a\":1,\"b\":2,\"c\":3}"),
                apply("{\"a\":1,\"b\":2}", "{\"c\":3}"));
    }

    @Test
    void overrides_existing_member() {
        assertEquals(read("{\"a\":99,\"b\":2}"),
                apply("{\"a\":1,\"b\":2}", "{\"a\":99}"));
    }

    /** RFC 7396 §1 : null dans le patch supprime le member. */
    @Test
    void null_member_in_patch_removes_target_member() {
        assertEquals(read("{\"b\":2}"),
                apply("{\"a\":1,\"b\":2}", "{\"a\":null}"));
    }

    /** Suppression d'un membre absent : no-op. */
    @Test
    void null_for_absent_member_is_noop() {
        assertEquals(read("{\"a\":1}"),
                apply("{\"a\":1}", "{\"b\":null}"));
    }

    /** Fusion récursive des sous-objets. */
    @Test
    void recursive_merge_for_nested_objects() {
        assertEquals(
                read("{\"o\":{\"a\":1,\"b\":99,\"c\":3}}"),
                apply("{\"o\":{\"a\":1,\"b\":2,\"c\":3}}", "{\"o\":{\"b\":99}}"));
    }

    /** RFC 7396 §3 : un array dans le patch remplace l'array cible (pas de fusion). */
    @Test
    void arrays_in_patch_replace_target() {
        assertEquals(read("{\"a\":[7,8]}"),
                apply("{\"a\":[1,2,3]}", "{\"a\":[7,8]}"));
    }

    /** Si la cible n'est pas un objet et que le patch l'est, le patch remplace. */
    @Test
    void object_patch_replaces_non_object_target() {
        assertEquals(read("{\"a\":1}"),
                apply("\"string\"", "{\"a\":1}"));
    }

    /** Si patch supprime tout, on obtient {} vide (et pas null). */
    @Test
    void empty_object_patch_keeps_target_unchanged() {
        assertEquals(read("{\"a\":1}"), apply("{\"a\":1}", "{}"));
    }

    /** RFC 7396 §3 examples — quelques cas canoniques. */
    @Test
    void appendix_examples() {
        // {"a":"b","c":{"d":"e","f":"g"}} + {"a":"z","c":{"f":null}} = {"a":"z","c":{"d":"e"}}
        assertEquals(
                read("{\"a\":\"z\",\"c\":{\"d\":\"e\"}}"),
                apply("{\"a\":\"b\",\"c\":{\"d\":\"e\",\"f\":\"g\"}}", "{\"a\":\"z\",\"c\":{\"f\":null}}"));

        // {"a":[{"b":"c"}]} + {"a":[1]} = {"a":[1]}
        assertEquals(
                read("{\"a\":[1]}"),
                apply("{\"a\":[{\"b\":\"c\"}]}", "{\"a\":[1]}"));
    }

    /** diff(source, target) doit produire un patch qui appliqué à source donne target. */
    @Test
    void diff_then_apply_recovers_target() {
        var source = read("{\"a\":1,\"b\":2,\"c\":{\"x\":1}}");
        var target = read("{\"a\":1,\"b\":99,\"c\":{\"x\":1,\"y\":2},\"d\":\"new\"}");
        JsonMergePatch diff = ChampollionJsonMergePatch.diff(source, target);
        assertEquals(target, diff.apply(source));
    }

    @Test
    void diff_emits_null_for_removed_members() {
        var source = read("{\"a\":1,\"b\":2}");
        var target = read("{\"a\":1}");
        JsonMergePatch diff = ChampollionJsonMergePatch.diff(source, target);
        // Le patch doit contenir {"b":null}
        var asJson = diff.toJsonValue();
        assertEquals(JsonValue.NULL, ((jakarta.json.JsonObject) asJson).get("b"));
    }

    @Test
    void Json_createMergePatch_returns_champollion() {
        var p = Json.createMergePatch(read("{\"a\":1}"));
        assertEquals(read("{\"a\":1}"), p.apply(read("{}")));
    }
}
