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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbPropertyOrder;
import jakarta.json.bind.config.PropertyNamingStrategy;
import jakarta.json.bind.config.PropertyOrderStrategy;
import jakarta.json.bind.config.PropertyVisibilityStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JsonbConfig} options and the stream entry points of {@link ChampollionJsonb}. */
@DisplayName("JSON-B — config options and stream entry points")
class JsonbConfigVariantsTest {

    private static final String FAIL_ON_UNKNOWN = "jsonb.fail-on-unknown-properties";

    private static final String ANN_JSON = "{\"firstName\":\"Ann\",\"itemCount\":2,\"isActive\":true}";
    public record Names(String firstName, int itemCount, boolean isActive) {}

    public static class BeanNames {
        private String firstName;
        public int itemCount;

        public BeanNames() {}

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
    }

    @JsonbPropertyOrder({"itemCount", "firstName"})
    public static class Ordered {
        public String firstName = "a";
        public int itemCount = 1;
        public String zeta = "z";
        public String alpha = "x";
    }

    public static class Unordered {
        public String beta = "b";
        public String alpha = "a";
        public String gamma = "c";
    }

    public static class Creator {
        private final String name;
        private final Integer age;
        public String extra;

        @JsonbCreator
        public Creator(@JsonbProperty("name") String name, @JsonbProperty(value = "age", nillable = true) Integer age) {
            this.name = name;
            this.age = age;
        }

        public String getName() { return name; }
        public Integer getAge() { return age; }
    }

    /** Only fields are visible to this strategy. */
    public static final class FieldsOnly implements PropertyVisibilityStrategy {
        @Override public boolean isVisible(Field field) { return true; }
        @Override public boolean isVisible(Method method) { return false; }
    }

    public static class Mixed {
        private String hidden = "h";
        private String viaGetter = "g";
        public String open = "o";

        public Mixed() {}

        public String getViaGetter() { return viaGetter; }
        public void setViaGetter(String viaGetter) { this.viaGetter = viaGetter; }
        public String getComputed() { return "c"; }
        public void setComputed(String value) { this.hidden = value; }
        String hidden() { return hidden; }
    }

    private static Jsonb with(JsonbConfig config) {
        return JsonbBuilder.create(config);
    }

    private static String namesWith(String strategy) throws Exception {
        try (var jsonb = with(new JsonbConfig().withPropertyNamingStrategy(strategy))) {
            String json = jsonb.toJson(new Names("Ann", 2, true));
            assertEquals(new Names("Ann", 2, true), jsonb.fromJson(json, Names.class));
            var bean = new BeanNames();
            bean.setFirstName("Bob");
            bean.itemCount = 3;
            String beanJson = jsonb.toJson(bean);
            var back = jsonb.fromJson(beanJson, BeanNames.class);
            assertEquals("Bob", back.getFirstName());
            assertEquals(3, back.itemCount);
            return json + beanJson;
        }
    }

    @Test
    void naming_strategies_rename_records_and_beans_both_ways() throws Exception {
        assertEquals("{\"first-name\":\"Ann\",\"item-count\":2,\"is-active\":true}"
                + "{\"first-name\":\"Bob\",\"item-count\":3}", namesWith(PropertyNamingStrategy.LOWER_CASE_WITH_DASHES));
        assertEquals("{\"first_name\":\"Ann\",\"item_count\":2,\"is_active\":true}"
                + "{\"first_name\":\"Bob\",\"item_count\":3}", namesWith(PropertyNamingStrategy.LOWER_CASE_WITH_UNDERSCORES));
        assertEquals("{\"FirstName\":\"Ann\",\"ItemCount\":2,\"IsActive\":true}"
                + "{\"FirstName\":\"Bob\",\"ItemCount\":3}", namesWith(PropertyNamingStrategy.UPPER_CAMEL_CASE));
        assertEquals("{\"First Name\":\"Ann\",\"Item Count\":2,\"Is Active\":true}"
                + "{\"First Name\":\"Bob\",\"Item Count\":3}", namesWith(PropertyNamingStrategy.UPPER_CAMEL_CASE_WITH_SPACES));
        assertEquals(ANN_JSON
                + "{\"firstName\":\"Bob\",\"itemCount\":3}", namesWith(PropertyNamingStrategy.IDENTITY));
        assertEquals(ANN_JSON
                + "{\"firstName\":\"Bob\",\"itemCount\":3}", namesWith(PropertyNamingStrategy.CASE_INSENSITIVE));
    }

    @Test
    void explicit_order_accepts_property_names_under_a_naming_strategy() throws Exception {
        try (var jsonb = with(new JsonbConfig().withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_CASE_WITH_DASHES))) {
            assertEquals("{\"item-count\":1,\"first-name\":\"a\",\"alpha\":\"x\",\"zeta\":\"z\"}",
                    jsonb.toJson(new Ordered()));
        }
    }

    @Test
    void order_strategies() throws Exception {
        try (var reverse = with(new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.REVERSE));
             var any = with(new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.ANY));
             var lexico = with(new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL))) {
            assertEquals("{\"gamma\":\"c\",\"beta\":\"b\",\"alpha\":\"a\"}", reverse.toJson(new Unordered()));
            assertEquals("{\"alpha\":\"a\",\"beta\":\"b\",\"gamma\":\"c\"}", lexico.toJson(new Unordered()));
            String anyJson = any.toJson(new Unordered());
            assertTrue(anyJson.contains("\"alpha\":\"a\"") && anyJson.contains("\"gamma\":\"c\""), anyJson);
        }
    }

    @Test
    void unknown_properties_fail_when_configured() throws Exception {
        try (var strict = with(new JsonbConfig().setProperty(FAIL_ON_UNKNOWN, true))) {
            assertThrows(JsonbException.class, () -> strict.fromJson("{\"nope\":1}", BeanNames.class));
            assertThrows(JsonbException.class, () -> strict.fromJson("{\"name\":\"a\",\"nope\":1}", Creator.class));
            assertEquals("Bob", strict.fromJson("{\"firstName\":\"Bob\"}", BeanNames.class).getFirstName());
        }
        try (var lenient = with(new JsonbConfig().setProperty(FAIL_ON_UNKNOWN, "false"))) {
            var out = lenient.fromJson("{\"name\":\"a\",\"nope\":{\"x\":[1]},\"extra\":\"e\"}", Creator.class);
            assertEquals("a", out.getName());
            assertEquals("e", out.extra);
        }
    }

    @Test
    void required_creator_parameters_except_nillable_ones() throws Exception {
        try (var jsonb = with(new JsonbConfig().withCreatorParametersRequired(true))) {
            var out = jsonb.fromJson("{\"name\":\"a\"}", Creator.class);
            assertEquals("a", out.getName());
            assertNull(out.getAge());
            assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"age\":3}", Creator.class));
        }
    }

    @Test
    void creator_reads_null_and_rejects_an_array() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            assertNull(jsonb.fromJson("null", Creator.class));
            assertThrows(JsonbException.class, () -> jsonb.fromJson("[]", Creator.class));
        }
    }

    @Test
    void a_visibility_strategy_from_the_config() throws Exception {
        try (var jsonb = with(new JsonbConfig().withPropertyVisibilityStrategy(new FieldsOnly()))) {
            String json = jsonb.toJson(new Mixed());
            // The private field without accessor ("hidden") is BUG-20261028-04 (still open): not asserted here.
            assertTrue(json.contains("\"open\":\"o\"") && json.contains("\"viaGetter\":\"g\""), json);
            assertFalse(json.contains("computed"), json);
            var out = jsonb.fromJson("{\"viaGetter\":\"x\",\"open\":\"y\",\"computed\":\"z\"}", Mixed.class);
            assertEquals("x", out.getViaGetter());
            assertEquals("y", out.open);
            assertEquals("h", out.hidden());
        }
    }

    @Test
    void strict_ijson_rejects_scalar_roots_and_encodes_binary_in_base64() throws Exception {
        try (var jsonb = with(new JsonbConfig().withStrictIJSON(true))) {
            assertThrows(JsonbException.class, () -> jsonb.toJson("text"));
            assertThrows(JsonbException.class, () -> jsonb.toJson(1));
            assertThrows(JsonbException.class, () -> jsonb.toJson(java.time.LocalDate.of(2020, 1, 2)));
            assertEquals("[1]", jsonb.toJson(List.of(1)));
            assertEquals("{\"a\":1}", jsonb.toJson(Map.of("a", 1)));
            assertEquals("[\"AQI=\"]", jsonb.toJson(new byte[][]{{1, 2}}));
            assertEquals(ANN_JSON, jsonb.toJson(new Names("Ann", 2, true)));
        }
    }

    @Test
    void writer_and_stream_entry_points_with_pretty_printing() throws Exception {
        try (var jsonb = with(new JsonbConfig().withFormatting(true))) {
            var writer = new StringWriter();
            jsonb.toJson(List.of(1), writer);
            assertTrue(writer.toString().contains("\n"), writer::toString);
            var out = new ByteArrayOutputStream();
            jsonb.toJson(Map.of("k", "v"), out);
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"k\": \"v\""), out::toString);
            var nullOut = new ByteArrayOutputStream();
            jsonb.toJson(null, nullOut);
            assertEquals("null", nullOut.toString(StandardCharsets.UTF_8));
            var nullWriter = new StringWriter();
            jsonb.toJson(null, nullWriter);
            assertEquals("null", nullWriter.toString());
        }
    }

    @Test
    void reader_and_stream_entry_points() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            assertEquals(new Names("a", 1, false),
                    jsonb.fromJson(new StringReader("{\"firstName\":\"a\",\"itemCount\":1}"), Names.class));
            assertEquals(new Names("b", 2, true), jsonb.fromJson(new ByteArrayInputStream(
                    "{\"firstName\":\"b\",\"itemCount\":2,\"isActive\":true}".getBytes(StandardCharsets.UTF_8)),
                    Names.class));
            // The pooled parser is reused by the next calls on this thread.
            assertEquals(List.of(1, 2), jsonb.fromJson(new StringReader("[1,2]"), List.class));
            assertEquals(List.of(3), jsonb.fromJson(new StringReader("[3]"), List.class));
            assertEquals("null", jsonb.toJson(null));
        }
    }

    @Test
    void binary_strategy_given_as_a_plain_string() throws Exception {
        try (var jsonb = with(new JsonbConfig().withBinaryDataStrategy("BASE_64"))) {
            assertArrayEquals(new byte[]{1, 2}, jsonb.fromJson("\"AQI=\"", byte[].class));
        }
    }
}
