package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;

/**
 * Internal {@link SerializationContext} implementation provided to custom
 * {@code JsonbSerializer}s so they can delegate recursive serialization to
 * Champollion.
 */
final class ChampollionSerializationContext implements SerializationContext {

    private final RuntimeBindingRegistry registry;

    ChampollionSerializationContext(RuntimeBindingRegistry registry) {
        this.registry = registry;
    }

    @Override public <T> void serialize(String key, T value, JsonGenerator generator) {
        if (value == null) {
            generator.writeNull(key);
            return;
        }
        generator.writeKey(key);
        serialize(value, generator);
    }

    @Override public <T> void serialize(T value, JsonGenerator generator) {
        if (value == null) { generator.writeNull(); return; }
        BindingWriter w = registry.writerFor(value.getClass());
        w.write(generator, value);
    }
}
