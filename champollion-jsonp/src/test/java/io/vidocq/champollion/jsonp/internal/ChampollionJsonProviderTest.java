package io.vidocq.champollion.jsonp.internal;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("ChampollionJsonProvider — entry point Json.* (M1.4)")
class ChampollionJsonProviderTest {

    @Test
    void service_loader_resolves_to_champollion() {
        // Quand l'unique JsonProvider sur le classpath est Champollion, Json.* doit le sélectionner.
        JsonProvider p = JsonProvider.provider();
        assertInstanceOf(ChampollionJsonProvider.class, p);
    }

    @Test
    void Json_createParser_reader_returns_champollion_parser() {
        try (JsonParser p = Json.createParser(new StringReader("[1]"))) {
            assertInstanceOf(ChampollionJsonParser.class, p);
            assertEquals(JsonParser.Event.START_ARRAY, p.next());
            assertEquals(JsonParser.Event.VALUE_NUMBER, p.next());
            assertEquals(1, p.getInt());
            assertEquals(JsonParser.Event.END_ARRAY, p.next());
        }
    }

    @Test
    void Json_createReader_returns_champollion_reader() {
        try (var r = Json.createReader(new StringReader("{\"a\":1}"))) {
            assertInstanceOf(ChampollionJsonReader.class, r);
            JsonObject o = r.readObject();
            assertEquals(1, o.getInt("a"));
        }
    }

    @Test
    void Json_createGenerator_writer_works_end_to_end() {
        var sw = new StringWriter();
        try (var g = Json.createGenerator(sw)) {
            g.writeStartArray().write(1).write("x").writeEnd();
        }
        assertEquals("[1,\"x\"]", sw.toString());
    }

    @Test
    void Json_createWriter_serializes_object_model() {
        var sw = new StringWriter();
        var arr = Json.createArrayBuilder().add(1).add(2).build();
        try (var w = Json.createWriter(sw)) {
            w.write(arr);
        }
        assertEquals("[1,2]", sw.toString());
    }

    @Test
    void Json_createObjectBuilder_returns_champollion_builder() {
        var b = Json.createObjectBuilder();
        assertInstanceOf(ChampollionJsonObjectBuilder.class, b);
        var o = b.add("k", "v").build();
        assertEquals("{\"k\":\"v\"}", o.toString());
    }

    @Test
    void Json_createArrayBuilder_returns_champollion_builder() {
        var b = Json.createArrayBuilder();
        assertInstanceOf(ChampollionJsonArrayBuilder.class, b);
        JsonArray a = b.add(true).addNull().build();
        assertEquals("[true,null]", a.toString());
    }

    @Test
    void Json_value_constants_round_trip() {
        // TRUE/FALSE/NULL passent par les singletons spec, indépendants du provider.
        var sw = new StringWriter();
        try (var g = Json.createGenerator(sw)) {
            g.writeStartArray().write(true).write(false).writeNull().writeEnd();
        }
        assertEquals("[true,false,null]", sw.toString());
    }

    @Test
    void roundtrip_via_input_output_streams_uses_utf8() {
        var json = "{\"é\":\"à\"}";
        var bytes = json.getBytes(StandardCharsets.UTF_8);

        try (var r = Json.createReader(new ByteArrayInputStream(bytes))) {
            JsonObject o = r.readObject();
            assertEquals("à", o.getString("é"));
        }

        var out = new ByteArrayOutputStream();
        try (var w = Json.createWriter(out)) {
            w.write(Json.createObjectBuilder().add("é", "à").build());
        }
        assertEquals(json, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void provider_resolution_is_consistent() {
        // The spec does not guarantee caching of the instance; we just verify
        // that each call returns a non-null instance of the expected provider.
        assertNotNull(JsonProvider.provider());
        assertInstanceOf(ChampollionJsonProvider.class, JsonProvider.provider());
    }
}
