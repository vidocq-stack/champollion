package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;

/**
 * Implémentation principale de {@link Jsonb}. M4.1 :
 * <ul>
 *   <li>écriture en mode runtime via {@link RuntimeBindingRegistry}</li>
 *   <li>lecture reportée à M4.2</li>
 * </ul>
 *
 * <p>Pas de {@code synchronized} — les caches utilisent {@link ClassValue}.</p>
 */
public final class ChampollionJsonb implements Jsonb {

    private final JsonbConfig config;
    private final JsonProvider jsonProvider;
    private final RuntimeBindingRegistry writeRegistry;
    private final RuntimeReadRegistry readRegistry;

    ChampollionJsonb(JsonbConfig config, JsonProvider jsonProvider) {
        this.config = config;
        this.jsonProvider = jsonProvider;
        this.writeRegistry = new RuntimeBindingRegistry();
        this.readRegistry = new RuntimeReadRegistry();
    }

    // ===== toJson =====

    @Override public String toJson(Object object) {
        if (object == null) return "null";
        return toJson(object, object.getClass());
    }

    @Override public String toJson(Object object, Type runtimeType) {
        var sw = new StringWriter();
        toJson(object, runtimeType, sw);
        return sw.toString();
    }

    @Override public void toJson(Object object, Writer writer) {
        toJson(object, object == null ? Object.class : object.getClass(), writer);
    }

    @Override public void toJson(Object object, Type runtimeType, Writer writer) {
        try (JsonGenerator g = jsonProvider.createGenerator(writer)) {
            if (object == null) {
                g.writeNull();
            } else {
                BindingWriter w = writeRegistry.writerFor(runtimeType);
                w.write(g, object);
            }
        }
    }

    @Override public void toJson(Object object, OutputStream stream) {
        toJson(object, object == null ? Object.class : object.getClass(), stream);
    }

    @Override public void toJson(Object object, Type runtimeType, OutputStream stream) {
        try (JsonGenerator g = jsonProvider.createGenerator(stream)) {
            if (object == null) {
                g.writeNull();
            } else {
                BindingWriter w = writeRegistry.writerFor(runtimeType);
                w.write(g, object);
            }
        }
    }

    // ===== fromJson =====

    @Override public <T> T fromJson(String str, Class<T> type) {
        return fromJson(new StringReader(str), (Type) type);
    }

    @Override public <T> T fromJson(String str, Type runtimeType) {
        return fromJson(new StringReader(str), runtimeType);
    }

    @Override public <T> T fromJson(Reader reader, Class<T> type) {
        return fromJson(reader, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(Reader reader, Type runtimeType) {
        try (JsonParser p = jsonProvider.createParser(reader)) {
            BindingReader r = readRegistry.readerFor(runtimeType);
            return (T) r.read(p);
        }
    }

    @Override public <T> T fromJson(InputStream stream, Class<T> type) {
        return fromJson(stream, (Type) type);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T fromJson(InputStream stream, Type runtimeType) {
        try (JsonParser p = jsonProvider.createParser(stream)) {
            BindingReader r = readRegistry.readerFor(runtimeType);
            return (T) r.read(p);
        }
    }

    @Override public void close() throws Exception { /* nothing held */ }
}
