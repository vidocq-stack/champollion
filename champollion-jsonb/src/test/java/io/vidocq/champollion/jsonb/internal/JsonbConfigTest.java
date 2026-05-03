package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M4.7 — JsonbConfig properties")
class JsonbConfigTest {

    @Nested
    @DisplayName("JSONB_FORMATTING — pretty printing")
    class Formatting {

        record Coord(int x, int y) {}

        @Test
        void pretty_printing_off_by_default() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Coord(3, 4));
                assertEquals("{\"x\":3,\"y\":4}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void pretty_printing_on_uses_indentation() {
            var config = new JsonbConfig().withFormatting(true);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Coord(3, 4));
                // 4 espaces d'indent + LF entre chaque membre
                assertEquals("{\n    \"x\": 3,\n    \"y\": 4\n}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("JSONB_NULL_VALUES — global null inclusion")
    class NullValues {

        public static class Box {
            public String name;
            public String content;
            public Box() {}
            public Box(String n, String c) { name = n; content = c; }
        }

        @Test
        void null_values_omitted_by_default() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Box("crate", null));
                assertEquals("{\"name\":\"crate\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void null_values_included_when_config_true() {
            var config = new JsonbConfig().withNullValues(true);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Box("crate", null));
                // Les deux propriétés sont incluses, content est null.
                assertTrue(json.contains("\"content\":null"),
                        "JSONB_NULL_VALUES=true doit forcer l'inclusion. JSON = " + json);
                assertTrue(json.contains("\"name\":\"crate\""));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("JSONB_DATE_FORMAT — global date pattern")
    class DateFormat {

        record Event(String name, LocalDate when) {}

        @Test
        void global_date_format_applied_to_localdate() {
            var config = new JsonbConfig().withDateFormat("dd/MM/yyyy", null);
            try (var j = JsonbBuilder.create(config)) {
                String json = j.toJson(new Event("Demo", LocalDate.of(2026, 5, 3)));
                assertEquals("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void global_date_format_used_for_read() {
            var config = new JsonbConfig().withDateFormat("dd/MM/yyyy", null);
            try (var j = JsonbBuilder.create(config)) {
                Event ev = j.fromJson("{\"name\":\"Demo\",\"when\":\"03/05/2026\"}", Event.class);
                assertEquals(LocalDate.of(2026, 5, 3), ev.when());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
