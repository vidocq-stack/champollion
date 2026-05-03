package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.stream.JsonParser;

import java.lang.reflect.Type;

/**
 * Implémentation interne de {@link DeserializationContext} fournie aux
 * {@code JsonbDeserializer} customs pour leur permettre de déléguer la désérialisation
 * récursive à Champollion.
 */
final class ChampollionDeserializationContext implements DeserializationContext {

    private final RuntimeReadRegistry registry;

    ChampollionDeserializationContext(RuntimeReadRegistry registry) {
        this.registry = registry;
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T deserialize(Class<T> clazz, JsonParser parser) {
        return (T) registry.readerFor(clazz).read(parser);
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T deserialize(Type type, JsonParser parser) {
        return (T) registry.readerFor(type).read(parser);
    }
}
