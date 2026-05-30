package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.spi.JsonbProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Champollion JSON-B 3.0 — toJson (M4.1, runtime mode)")
class ChampollionJsonbWriteTest {

    @Nested
    @DisplayName("ServiceLoader / SPI")
    class Spi {

        @Test
        void provider_resolves_to_champollion() {
            JsonbProvider p = JsonbProvider.provider();
            assertInstanceOf(ChampollionJsonbProvider.class, p);
        }

        @Test
        void JsonbBuilder_create_returns_champollion_jsonb() {
            try (Jsonb jsonb = JsonbBuilder.create()) {
                assertInstanceOf(ChampollionJsonb.class, jsonb);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("Primitives & wrappers")
    class Primitives {

        private Jsonb jsonb() { return JsonbBuilder.create(); }

        @Test
        void writes_string_with_quotes_and_escapes() {
            try (var j = jsonb()) {
                assertEquals("\"hello\"", j.toJson("hello"));
                assertEquals("\"a\\\"b\"", j.toJson("a\"b"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void writes_numbers() {
            try (var j = jsonb()) {
                assertEquals("42", j.toJson(42));
                assertEquals("42", j.toJson(42L));
                assertEquals("3.14", j.toJson(3.14));
                assertEquals("1.5", j.toJson(new BigDecimal("1.5")));
                assertEquals("12345678901234567890", j.toJson(new BigInteger("12345678901234567890")));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void writes_booleans_and_null() {
            try (var j = jsonb()) {
                assertEquals("true", j.toJson(true));
                assertEquals("false", j.toJson(false));
                assertEquals("null", j.toJson(null, Object.class));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("java.time types — ISO-8601 default")
    class TimeTypes {

        private Jsonb jsonb() { return JsonbBuilder.create(); }

        @Test
        void writes_instant_iso8601() {
            try (var j = jsonb()) {
                Instant epoch = Instant.parse("2024-03-15T10:30:00Z");
                assertEquals("\"2024-03-15T10:30:00Z\"", j.toJson(epoch));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void writes_localdate_iso8601() {
            try (var j = jsonb()) {
                assertEquals("\"2024-03-15\"", j.toJson(LocalDate.of(2024, 3, 15)));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void writes_localdatetime_iso8601() {
            try (var j = jsonb()) {
                var v = LocalDateTime.of(2024, 3, 15, 10, 30);
                assertTrue(j.toJson(v).startsWith("\"2024-03-15T10:30"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("UUID / enum")
    class Misc {

        private Jsonb jsonb() { return JsonbBuilder.create(); }

        @Test
        void uuid_as_string() {
            try (var j = jsonb()) {
                var u = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
                assertEquals("\"550e8400-e29b-41d4-a716-446655440000\"", j.toJson(u));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        enum Status { ACTIVE, INACTIVE }

        @Test
        void enum_as_string_name() {
            try (var j = jsonb()) {
                assertEquals("\"ACTIVE\"", j.toJson(Status.ACTIVE));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Records — automatic property discovery")
    class Records {

        record Point(int x, int y) {}
        record Person(String name, int age, Point position) {}

        private Jsonb jsonb() { return JsonbBuilder.create(); }

        @Test
        void simple_record_serializes_fields_in_order() {
            try (var j = jsonb()) {
                assertEquals("{\"x\":3,\"y\":4}", j.toJson(new Point(3, 4)));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void nested_record() {
            try (var j = jsonb()) {
                var p = new Person("Alice", 30, new Point(1, 2));
                assertEquals("{\"name\":\"Alice\",\"age\":30,\"position\":{\"x\":1,\"y\":2}}", j.toJson(p));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void record_with_null_field() {
            try (var j = jsonb()) {
                // Champ null : par défaut en JSON-B 3.0, le membre est *omis*. Spec §3.14.2.
                var p = new Person(null, 0, null);
                String json = j.toJson(p);
                assertEquals("{\"age\":0}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("POJOs with public fields")
    class Pojos {

        public static final class PublicFields {
            public String name;
            public int count;
            public PublicFields() {}
            public PublicFields(String name, int count) { this.name = name; this.count = count; }
        }

        private Jsonb jsonb() { return JsonbBuilder.create(); }

        @Test
        void pojo_with_public_fields_serializes_them() {
            try (var j = jsonb()) {
                var p = new PublicFields("Alice", 7);
                String json = j.toJson(p);
                // Field order not guaranteed by spec; we verify the content.
                assertTrue(json.contains("\"name\":\"Alice\""));
                assertTrue(json.contains("\"count\":7"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
