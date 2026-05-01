package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonParser;

/**
 * Lit une valeur Java depuis un {@link JsonParser}. À l'entrée, le parser est positionné
 * <em>juste avant</em> l'event correspondant à la valeur (le reader appelle {@code next()}
 * lui-même pour consommer l'event de tête).
 */
@FunctionalInterface
interface BindingReader {
    Object read(JsonParser p);
}
