package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.stream.JsonParser;

import java.lang.reflect.Type;

/**
 * Internal {@link DeserializationContext} implementation provided to custom
 * {@code JsonbDeserializer}s so they can delegate recursive deserialization to
 * Champollion.
 */
final class ChampollionDeserializationContext implements DeserializationContext {

    private final RuntimeReadRegistry registry;

    ChampollionDeserializationContext(RuntimeReadRegistry registry) {
        this.registry = registry;
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T deserialize(Class<T> clazz, JsonParser parser) {
        return (T) registry.readerFor(clazz).read(adjust(parser));
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T deserialize(Type type, JsonParser parser) {
        return (T) registry.readerFor(type).read(adjust(parser));
    }

    /**
     * If the caller (a custom JsonbDeserializer) has already consumed the value's
     * start event (START_OBJECT / START_ARRAY / VALUE_xxx), replay it so that
     * Champollion's standard BindingReaders (which call {@code parser.next()} first)
     * still see that event.
     */
    private JsonParser adjust(JsonParser parser) {
        var ev = parser.currentEvent();
        if (ev == null) return parser;
        switch (ev) {
            case START_OBJECT, START_ARRAY,
                 VALUE_STRING, VALUE_NUMBER, VALUE_TRUE, VALUE_FALSE, VALUE_NULL -> {
                return new ReplayJsonParser(ev, parser);
            }
            default -> { return parser; }
        }
    }
}
