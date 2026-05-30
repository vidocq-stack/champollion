package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonParser;

/**
 * Reads a Java value from a {@link JsonParser}. On entry, the parser is positioned
 * <em>just before</em> the event corresponding to the value (the reader calls
 * {@code next()} itself to consume the leading event).
 */
@FunctionalInterface
interface BindingReader {
    Object read(JsonParser p);
}
