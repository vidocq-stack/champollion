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
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonConfig;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChampollionJsonProvider — factories, builders, values and patch builder")
class ProviderFactoriesTest {

    private static final String E_ACUTE_ARRAY = "[\"é\"]";
    private static final ChampollionJsonProvider PROVIDER = new ChampollionJsonProvider();
    private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;
    private static final String OBJECT_TEXT = "{\"a\":1}";
    private static final String KEY_STRATEGY = JsonConfig.KEY_STRATEGY;

    private static JsonObject object(String json) {
        try (JsonReader r = PROVIDER.createReader(new StringReader(json))) {
            return r.readObject();
        }
    }

    private static JsonArray array(String json) {
        try (JsonReader r = PROVIDER.createReader(new StringReader(json))) {
            return r.readArray();
        }
    }

    private static InputStream bytes(String text, Charset cs) {
        return new ByteArrayInputStream(text.getBytes(cs));
    }

    private static List<JsonParser.Event> events(JsonParser p) {
        List<JsonParser.Event> out = new ArrayList<>();
        while (p.hasNext()) out.add(p.next());
        p.close();
        return out;
    }

    @Nested
    class ParserFactory {

        private final JsonParserFactory factory = PROVIDER.createParserFactory(Map.of("unknown", "x"));

        @Test
        void keeps_no_unsupported_property() {
            assertTrue(factory.getConfigInUse().isEmpty());
            assertTrue(PROVIDER.createParserFactory(null).getConfigInUse().isEmpty());
        }

        @Test
        void every_source_yields_the_same_events() {
            List<JsonParser.Event> expected = List.of(JsonParser.Event.START_OBJECT, JsonParser.Event.KEY_NAME,
                    JsonParser.Event.VALUE_NUMBER, JsonParser.Event.END_OBJECT);
            assertEquals(expected, events(factory.createParser(new StringReader(OBJECT_TEXT))));
            assertEquals(expected, events(factory.createParser(bytes(OBJECT_TEXT, StandardCharsets.UTF_8))));
            assertEquals(expected, events(factory.createParser(bytes(OBJECT_TEXT, UTF_16LE), UTF_16LE)));
            assertEquals(expected, events(factory.createParser(object(OBJECT_TEXT))));
            assertEquals(List.of(JsonParser.Event.START_ARRAY, JsonParser.Event.END_ARRAY),
                    events(factory.createParser(array("[]"))));
        }
    }

    @Nested
    class GeneratorFactory {

        @Test
        void pretty_printing_is_the_only_property_kept() {
            Map<String, Object> config = new HashMap<>();
            config.put(JsonGenerator.PRETTY_PRINTING, "true");
            config.put("other", 1);
            JsonGeneratorFactory factory = PROVIDER.createGeneratorFactory(config);
            assertEquals(Map.of(JsonGenerator.PRETTY_PRINTING, "true"), factory.getConfigInUse());
            StringWriter w = new StringWriter();
            try (JsonGenerator g = factory.createGenerator(w)) {
                g.writeStartObject().write("a", 1).writeEnd();
            }
            assertTrue(w.toString().contains("\n"), w.toString());
        }

        @Test
        void a_non_boolean_pretty_value_means_compact() {
            JsonGeneratorFactory factory = PROVIDER.createGeneratorFactory(Map.of(JsonGenerator.PRETTY_PRINTING, 1));
            StringWriter w = new StringWriter();
            try (JsonGenerator g = factory.createGenerator(w)) {
                g.writeStartArray().write(1).writeEnd();
            }
            assertEquals("[1]", w.toString());
        }

        @Test
        void streams_are_written_in_the_requested_charset() {
            JsonGeneratorFactory factory = PROVIDER.createGeneratorFactory(null);
            assertTrue(factory.getConfigInUse().isEmpty());
            var utf8 = new ByteArrayOutputStream();
            try (JsonGenerator g = factory.createGenerator(utf8)) {
                g.writeStartArray().write("é").writeEnd();
            }
            assertEquals(E_ACUTE_ARRAY, utf8.toString(StandardCharsets.UTF_8));
            var utf8Explicit = new ByteArrayOutputStream();
            try (JsonGenerator g = factory.createGenerator(utf8Explicit, StandardCharsets.UTF_8)) {
                g.writeStartArray().write("é").writeEnd();
            }
            assertEquals(E_ACUTE_ARRAY, utf8Explicit.toString(StandardCharsets.UTF_8));
            var utf16 = new ByteArrayOutputStream();
            try (JsonGenerator g = factory.createGenerator(utf16, UTF_16LE)) {
                g.writeStartArray().write("é").writeEnd();
            }
            assertEquals(E_ACUTE_ARRAY, utf16.toString(UTF_16LE));
        }
    }

    @Nested
    class ReaderFactory {

        private static final String DUPLICATES = "{\"k\":1,\"k\":2}";

        @Test
        void key_strategy_is_kept_and_applied() {
            JsonReaderFactory first = PROVIDER.createReaderFactory(Map.of(KEY_STRATEGY, JsonConfig.KeyStrategy.FIRST,
                    "ignored", true));
            assertEquals(Map.of(KEY_STRATEGY, JsonConfig.KeyStrategy.FIRST), first.getConfigInUse());
            try (JsonReader r = first.createReader(new StringReader(DUPLICATES))) {
                assertEquals(1, r.readObject().getInt("k"));
            }
        }

        @Test
        void key_strategy_given_as_a_string() {
            JsonReaderFactory none = PROVIDER.createReaderFactory(Map.of(KEY_STRATEGY, "NONE"));
            try (JsonReader r = none.createReader(bytes(DUPLICATES, StandardCharsets.UTF_8))) {
                assertThrows(JsonException.class, r::readObject);
            }
        }

        @Test
        void an_unknown_key_strategy_type_means_last() {
            JsonReaderFactory last = PROVIDER.createReaderFactory(Map.of(KEY_STRATEGY, 42));
            try (JsonReader r = last.createReader(bytes(DUPLICATES, UTF_16LE), UTF_16LE)) {
                assertEquals(2, r.readObject().getInt("k"));
            }
            try (JsonReader r = PROVIDER.createReaderFactory(null).createReader(new StringReader(DUPLICATES))) {
                assertEquals(2, r.readObject().getInt("k"));
            }
        }

        @Test
        void read_errors_name_the_problem() {
            try (JsonReader r = PROVIDER.createReader(new StringReader("\"text\""))) {
                assertThrows(JsonException.class, r::read);
            }
            try (JsonReader r = PROVIDER.createReader(new StringReader("[1]"))) {
                assertThrows(JsonException.class, r::readObject);
            }
            try (JsonReader r = PROVIDER.createReader(new StringReader(OBJECT_TEXT))) {
                assertThrows(JsonException.class, r::readArray);
            }
            try (JsonReader r = PROVIDER.createReader(new StringReader(""))) {
                assertThrows(JsonException.class, r::readValue);
            }
            try (JsonReader r = PROVIDER.createReader(new StringReader("true"))) {
                assertSame(JsonValue.TRUE, r.readValue());
                assertThrows(IllegalStateException.class, r::readValue);
            }
        }

        @Test
        void a_reader_over_an_in_memory_parser_rebuilds_the_structure() {
            JsonObject source = object("{\"a\":[1,{\"b\":null}],\"c\":false}");
            try (JsonReader r = new ChampollionJsonReader(new JsonValueParser(source))) {
                assertEquals(source, r.readObject());
            }
            try (JsonReader r = new ChampollionJsonReader(new JsonValueParser(source), null)) {
                assertEquals(source, r.read());
            }
        }
    }

    @Nested
    class WriterFactory {

        @Test
        void writers_flush_the_stream_after_each_write() {
            JsonWriterFactory factory = PROVIDER.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
            assertEquals(Map.of(JsonGenerator.PRETTY_PRINTING, true), factory.getConfigInUse());
            var out = new ByteArrayOutputStream();
            JsonWriter w = factory.createWriter(out);
            w.writeArray(array("[1]"));
            assertTrue(out.size() > 0, "flushed after writeArray");
            w.close();
        }

        @Test
        void each_write_method_reaches_the_stream() {
            JsonWriterFactory factory = PROVIDER.createWriterFactory(null);
            var a = new ByteArrayOutputStream();
            try (JsonWriter w = factory.createWriter(a, UTF_16LE)) {
                w.writeObject(object(OBJECT_TEXT));
            }
            assertEquals(OBJECT_TEXT, a.toString(UTF_16LE));
            var b = new ByteArrayOutputStream();
            try (JsonWriter w = factory.createWriter(b)) {
                w.write((jakarta.json.JsonStructure) array("[true]"));
            }
            assertEquals("[true]", b.toString(StandardCharsets.UTF_8));
            var c = new ByteArrayOutputStream();
            try (JsonWriter w = PROVIDER.createWriter(c)) {
                w.write(JsonValue.NULL);
            }
            assertEquals("null", c.toString(StandardCharsets.UTF_8));
            var d = new StringWriter();
            try (JsonWriter w = factory.createWriter(d)) {
                w.write(PROVIDER.createValue("s"));
            }
            assertEquals("\"s\"", d.toString());
        }

        @Test
        void a_failing_flush_becomes_a_json_exception() {
            OutputStream broken = new OutputStream() {
                @Override public void write(int b) {
                    // accept the bytes
                }
                @Override public void flush() throws IOException {
                    throw new IOException("disk full");
                }
            };
            JsonWriter w = PROVIDER.createWriter(broken);
            JsonArray empty = array("[]");
            assertThrows(JsonException.class, () -> w.writeArray(empty));
        }
    }

    @Nested
    class Builders {

        @Test
        void a_map_converts_every_supported_value_type() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("x", 1);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("null", null);
            map.put("json", JsonValue.TRUE);
            map.put("string", "s");
            map.put("true", true);
            map.put("false", false);
            map.put("int", 7);
            map.put("long", 8L);
            map.put("decimal", new BigDecimal("1.5"));
            map.put("bigint", new BigInteger("123456789012345678901234567890"));
            map.put("double", 2.5d);
            map.put("map", nested);
            map.put("list", Arrays.asList(1, "two", null));
            JsonObject o = PROVIDER.createObjectBuilder(map).build();
            assertEquals(object("{\"null\":null,\"json\":true,\"string\":\"s\",\"true\":true,\"false\":false,"
                    + "\"int\":7,\"long\":8,\"decimal\":1.5,\"bigint\":123456789012345678901234567890,"
                    + "\"double\":2.5,\"map\":{\"x\":1},\"list\":[1,\"two\",null]}"), o);
        }

        @Test
        void map_conversion_errors() {
            Map<String, Object> nullKey = new HashMap<>();
            nullKey.put(null, 1);
            assertThrows(NullPointerException.class, () -> PROVIDER.createObjectBuilder(nullKey));
            Map<String, Object> badNested = Map.of("m", Map.of(1, "x"));
            assertThrows(IllegalArgumentException.class, () -> PROVIDER.createObjectBuilder(badNested));
            Map<String, Object> unsupported = Map.of("o", new Object());
            assertThrows(IllegalArgumentException.class, () -> PROVIDER.createObjectBuilder(unsupported));
        }

        @Test
        void null_sources_give_empty_builders() {
            assertEquals(JsonValue.EMPTY_JSON_OBJECT, PROVIDER.createObjectBuilder((Map<String, ?>) null).build());
            assertEquals(JsonValue.EMPTY_JSON_ARRAY,
                    PROVIDER.createArrayBuilder((java.util.Collection<?>) null).build());
        }

        @Test
        void copies_of_structures_are_equal() {
            JsonObject o = object("{\"a\":[1],\"b\":\"c\"}");
            assertEquals(o, PROVIDER.createObjectBuilder(o).build());
            JsonArray a = array("[1,\"x\",{}]");
            assertEquals(a, PROVIDER.createArrayBuilder(a).build());
            assertEquals(array("[1,\"x\",true]"), PROVIDER.createArrayBuilder(List.of(1, "x", true)).build());
        }

        @Test
        void builder_factory_delegates_to_the_provider() {
            JsonBuilderFactory f = PROVIDER.createBuilderFactory(Map.of("any", 1));
            assertTrue(f.getConfigInUse().isEmpty());
            assertEquals(JsonValue.EMPTY_JSON_OBJECT, f.createObjectBuilder().build());
            assertEquals(JsonValue.EMPTY_JSON_ARRAY, f.createArrayBuilder().build());
            JsonObject o = object(OBJECT_TEXT);
            assertEquals(o, f.createObjectBuilder(o).build());
            assertEquals(o, f.createObjectBuilder(Map.<String, Object>of("a", 1)).build());
            JsonArray a = array("[2]");
            assertEquals(a, f.createArrayBuilder(a).build());
            assertEquals(a, f.createArrayBuilder(List.of(2)).build());
        }
    }

    @Nested
    class Values {

        @Test
        void numbers_of_every_kind() {
            assertEquals(new BigDecimal("2.5"), PROVIDER.createValue(2.5d).bigDecimalValue());
            assertEquals(5L, PROVIDER.createValue(5L).longValue());
            assertEquals(new BigInteger("99999999999999999999"),
                    PROVIDER.createValue(new BigInteger("99999999999999999999")).bigIntegerValue());
            assertEquals(new BigDecimal("0.1"), PROVIDER.createValue(new BigDecimal("0.1")).bigDecimalValue());
        }

        @Test
        void the_number_overload_dispatches_on_the_runtime_type() {
            assertEquals(new BigDecimal("1.5"), PROVIDER.createValue((Number) new BigDecimal("1.5")).bigDecimalValue());
            assertEquals(BigInteger.TEN, PROVIDER.createValue((Number) BigInteger.TEN).bigIntegerValue());
            assertEquals(3, PROVIDER.createValue((Number) Integer.valueOf(3)).intValue());
            assertEquals(4L, PROVIDER.createValue((Number) Long.valueOf(4)).longValue());
            assertEquals(5, PROVIDER.createValue((Number) Short.valueOf((short) 5)).intValue());
            assertEquals(6, PROVIDER.createValue((Number) Byte.valueOf((byte) 6)).intValue());
            assertEquals(new BigDecimal("0.5"), PROVIDER.createValue((Number) Float.valueOf(0.5f)).bigDecimalValue());
            assertEquals(new BigDecimal("7.25"), PROVIDER.createValue((Number) Double.valueOf(7.25)).bigDecimalValue());
            assertEquals(9L, PROVIDER.createValue((Number) new AtomicInteger(9)).longValue());
        }

        @Test
        void non_finite_and_null_numbers_are_rejected() {
            assertThrows(NumberFormatException.class, () -> PROVIDER.createValue(Double.NaN));
            assertThrows(NumberFormatException.class, () -> PROVIDER.createValue(Double.POSITIVE_INFINITY));
            Number nan = Float.NaN;
            assertThrows(NumberFormatException.class, () -> PROVIDER.createValue(nan));
            Number infinite = Double.NEGATIVE_INFINITY;
            assertThrows(NumberFormatException.class, () -> PROVIDER.createValue(infinite));
            assertThrows(NullPointerException.class, () -> PROVIDER.createValue((Number) null));
        }
    }

    @Nested
    class PatchBuilder {

        @Test
        void every_operation_is_recorded_in_order() {
            JsonArray ops = PROVIDER.createPatchBuilder()
                    .add("/a", "s").add("/b", 1).add("/c", true).add("/d", JsonValue.NULL)
                    .replace("/a", "t").replace("/b", 2).replace("/c", false).replace("/d", JsonValue.TRUE)
                    .test("/a", "t").test("/b", 2).test("/c", false).test("/d", JsonValue.TRUE)
                    .move("/e", "/a").copy("/f", "/b").remove("/c")
                    .build().toJsonArray();
            assertEquals(15, ops.size());
            assertEquals(object("{\"op\":\"add\",\"path\":\"/c\",\"value\":true}"), ops.get(2));
            assertEquals(object("{\"op\":\"replace\",\"path\":\"/b\",\"value\":2}"), ops.get(5));
            assertEquals(object("{\"op\":\"test\",\"path\":\"/a\",\"value\":\"t\"}"), ops.get(8));
            assertEquals(object("{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/e\"}"), ops.get(12));
            assertEquals(object("{\"op\":\"copy\",\"from\":\"/b\",\"path\":\"/f\"}"), ops.get(13));
            assertEquals(object("{\"op\":\"remove\",\"path\":\"/c\"}"), ops.get(14));
        }

        @Test
        void the_built_patch_applies() {
            JsonObject target = PROVIDER.createPatchBuilder(array("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]"))
                    .copy("/y", "/x").move("/z", "/y").test("/z", 1)
                    .build().apply(object("{}"));
            assertEquals(object("{\"x\":1,\"z\":1}"), target);
        }

        @Test
        void a_failing_test_operation_rejects_the_patch() {
            var patch = PROVIDER.createPatchBuilder().test("/a", 2).build();
            JsonObject source = object(OBJECT_TEXT);
            assertThrows(JsonException.class, () -> patch.apply(source));
        }

        @Test
        void a_null_initial_array_starts_empty() {
            assertTrue(new ChampollionJsonPatchBuilder(null).build().toJsonArray().isEmpty());
        }
    }

    @Nested
    class SmallStreams {

        @Test
        void a_small_utf8_stream_is_decoded_and_closed() {
            AtomicBoolean closed = new AtomicBoolean();
            InputStream in = new ByteArrayInputStream("[\"héllo\"]".getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
            try (JsonParser p = PROVIDER.createParser(in)) {
                p.next();
                p.next();
                assertEquals("héllo", p.getString());
            }
            assertTrue(closed.get());
        }

        @Test
        void an_io_error_during_detection_is_a_json_exception() {
            InputStream failing = new InputStream() {
                @Override public int read() throws IOException {
                    throw new IOException("broken");
                }
            };
            JsonException e = assertThrows(JsonException.class, () -> PROVIDER.createParser(failing));
            assertEquals("broken", e.getCause().getMessage());
        }
    }
}
