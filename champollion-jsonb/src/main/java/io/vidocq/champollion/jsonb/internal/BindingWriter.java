package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonGenerator;

/**
 * Writes a Java value to a {@link JsonGenerator}. One instance per resolved class
 * (cache {@link ClassValue}). Stateless after construction.
 */
@FunctionalInterface
interface BindingWriter {
    void write(JsonGenerator g, Object value);
}
