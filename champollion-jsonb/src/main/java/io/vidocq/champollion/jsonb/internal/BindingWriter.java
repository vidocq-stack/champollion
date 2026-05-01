package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonGenerator;

/**
 * Écrit une valeur Java dans un {@link JsonGenerator}. Une instance par classe résolue
 * (cache {@link ClassValue}). Stateless après construction.
 */
@FunctionalInterface
interface BindingWriter {
    void write(JsonGenerator g, Object value);
}
