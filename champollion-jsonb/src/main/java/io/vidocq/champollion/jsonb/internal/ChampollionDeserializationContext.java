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
        return (T) registry.readerFor(clazz).read(adjust(parser));
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T deserialize(Type type, JsonParser parser) {
        return (T) registry.readerFor(type).read(adjust(parser));
    }

    /**
     * Si l'appelant (un JsonbDeserializer custom) a déjà consommé l'événement de début
     * de la valeur (START_OBJECT / START_ARRAY / VALUE_xxx), le re-jouer pour que les
     * BindingReader standards de Champollion (qui font {@code parser.next()} au début)
     * voient bien cet événement.
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
