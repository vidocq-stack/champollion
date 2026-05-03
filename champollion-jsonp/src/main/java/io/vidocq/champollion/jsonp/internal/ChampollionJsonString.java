package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonString;

/**
 * Implémentation immuable de {@link JsonString}. Spec Jakarta JSON-P §4.4.
 *
 * @param value chaîne décodée (escapes déjà résolus, pas de quotes englobantes)
 */
public record ChampollionJsonString(String value) implements JsonString {

    public ChampollionJsonString {
        if (value == null) throw new NullPointerException("value is null");
    }

    @Override public ValueType getValueType() { return ValueType.STRING; }

    @Override public String getString() { return value; }

    @Override public CharSequence getChars() { return value; }

    @Override public String toString() { return JsonStringEscaper.quote(value); }
}
