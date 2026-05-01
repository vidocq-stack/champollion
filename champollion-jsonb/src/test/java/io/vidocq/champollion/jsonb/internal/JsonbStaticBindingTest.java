package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("M5.1 — JsonbBinding SPI + static-first lookup")
class JsonbStaticBindingTest {

    /** Record cible. Sera généré par l'APT en M5.2. Ici on l'écrit à la main pour valider l'infrastructure. */
    public record Coord(int x, int y) {}

    /**
     * Binding statique écrit à la main. En M5.2, l'APT générera l'équivalent
     * mot pour mot. Sa présence dans la liste de bindings doit suffire à
     * court-circuiter le runtime introspectif pour le type {@code Coord}.
     */
    static class CoordStaticBinding implements JsonbBinding<Coord> {
        // Compteur pour vérifier que CE binding a bien été utilisé (et pas le runtime).
        static volatile int writeCalls = 0;
        static volatile int readCalls = 0;

        @Override public Class<Coord> type() { return Coord.class; }

        @Override public void write(JsonGenerator g, Coord value) {
            writeCalls++;
            g.writeStartObject();
            g.write("x", value.x());
            g.write("y", value.y());
            g.writeEnd();
        }

        @Override public Coord read(JsonParser p) {
            readCalls++;
            JsonParser.Event e = p.next();
            if (e != JsonParser.Event.START_OBJECT) throw new IllegalStateException("Expected START_OBJECT");
            int x = 0, y = 0;
            while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
                String key = p.getString();
                p.next();
                int v = p.getInt();
                if ("x".equals(key)) x = v;
                else if ("y".equals(key)) y = v;
            }
            return new Coord(x, y);
        }
    }

    private ChampollionJsonb jsonbWith(JsonbBinding<?>... bindings) {
        var builder = new ChampollionJsonbBuilder();
        builder.withConfig(new JsonbConfig());
        builder.withStaticBindings(List.of(bindings));
        return builder.buildChampollion();
    }

    @Nested
    @DisplayName("Lookup-first behavior")
    class LookupFirst {

        @Test
        void static_binding_used_for_write_when_registered() {
            CoordStaticBinding.writeCalls = 0;
            var jsonb = jsonbWith(new CoordStaticBinding());
            String json = jsonb.toJson(new Coord(3, 4));
            assertEquals("{\"x\":3,\"y\":4}", json);
            assertEquals(1, CoordStaticBinding.writeCalls,
                    "Le binding statique doit avoir été appelé pour le write.");
        }

        @Test
        void static_binding_used_for_read_when_registered() {
            CoordStaticBinding.readCalls = 0;
            var jsonb = jsonbWith(new CoordStaticBinding());
            Coord c = jsonb.fromJson("{\"x\":7,\"y\":8}", Coord.class);
            assertEquals(new Coord(7, 8), c);
            assertEquals(1, CoordStaticBinding.readCalls,
                    "Le binding statique doit avoir été appelé pour le read.");
        }

        @Test
        void runtime_fallback_when_no_static_binding_for_type() {
            // Coord a un binding statique, mais Pair n'en a pas → runtime.
            record Pair(String a, String b) {}
            CoordStaticBinding.writeCalls = 0;
            var jsonb = jsonbWith(new CoordStaticBinding());
            String json = jsonb.toJson(new Pair("hello", "world"));
            assertEquals("{\"a\":\"hello\",\"b\":\"world\"}", json);
            assertEquals(0, CoordStaticBinding.writeCalls,
                    "Le binding Coord ne doit pas être utilisé pour Pair.");
        }

        @Test
        void mix_static_and_runtime_in_same_jsonb() {
            // Coord = static, Pair = runtime, dans le même Jsonb.
            CoordStaticBinding.writeCalls = 0;
            CoordStaticBinding.readCalls = 0;
            var jsonb = jsonbWith(new CoordStaticBinding());

            assertEquals("{\"x\":1,\"y\":2}", jsonb.toJson(new Coord(1, 2)));
            assertEquals(new Coord(1, 2), jsonb.fromJson("{\"x\":1,\"y\":2}", Coord.class));

            record Pair(String k, String v) {}
            assertEquals("{\"k\":\"a\",\"v\":\"b\"}", jsonb.toJson(new Pair("a", "b")));

            assertEquals(1, CoordStaticBinding.writeCalls);
            assertEquals(1, CoordStaticBinding.readCalls);
        }
    }

    @Nested
    @DisplayName("ServiceLoader resolution")
    class ServiceLoaderResolution {

        @Test
        void default_jsonb_loads_bindings_via_serviceloader() {
            // Sans binding fourni explicitement, ChampollionJsonb doit charger
            // la liste depuis ServiceLoader<JsonbBinding>. M5.1 vérifie juste
            // que la liste est consultable et non-null (peut être vide en l'absence
            // de bindings statiques sur le classpath de test).
            var jsonb = (ChampollionJsonb) JsonbBuilder.create();
            assertNotNull(jsonb.staticBindingsView(),
                    "La vue immuable des bindings statiques ne doit jamais être null.");
        }

        @Test
        void duplicate_static_bindings_for_same_type_first_wins() {
            // Si deux bindings statiques annoncent le même type, le premier dans
            // l'ordre d'enregistrement gagne (déterministe).
            class A extends CoordStaticBinding { @Override public void write(JsonGenerator g, Coord v) {
                writeCalls++; g.writeStartObject(); g.write("from","A"); g.writeEnd();
            }}
            class B extends CoordStaticBinding { @Override public void write(JsonGenerator g, Coord v) {
                writeCalls++; g.writeStartObject(); g.write("from","B"); g.writeEnd();
            }}
            CoordStaticBinding.writeCalls = 0;
            var jsonb = jsonbWith(new A(), new B());
            String json = jsonb.toJson(new Coord(0, 0));
            assertEquals("{\"from\":\"A\"}", json);
        }
    }
}
