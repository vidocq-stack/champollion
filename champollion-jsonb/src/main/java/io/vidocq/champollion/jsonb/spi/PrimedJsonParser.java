package io.vidocq.champollion.jsonb.spi;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Wrapper {@link JsonParser} qui ré-émet un événement déjà consommé en lookahead,
 * puis délègue à un parser sous-jacent.
 *
 * <p>Utilitaire principalement consommé par le code généré par
 * {@code champollion-codegen-apt} : quand un binding parent appelle un
 * binding enfant pour un type imbriqué, l'enfant attend de pouvoir commencer
 * par {@link JsonParser#next()}, mais le parent a déjà consommé l'événement
 * de tête. Wrapper l'appel via cette classe restitue cet événement.</p>
 *
 * <p>Public car référencé par du code Java généré dans des packages tiers.</p>
 */
public final class PrimedJsonParser implements JsonParser {

    private JsonParser.Event primed;
    private final JsonParser delegate;

    public PrimedJsonParser(JsonParser.Event primed, JsonParser delegate) {
        this.primed = primed;
        this.delegate = delegate;
    }

    @Override public boolean hasNext() { return primed != null || delegate.hasNext(); }

    @Override public Event next() {
        if (primed != null) { var e = primed; primed = null; return e; }
        return delegate.next();
    }

    @Override public String getString() { return delegate.getString(); }
    @Override public boolean isIntegralNumber() { return delegate.isIntegralNumber(); }
    @Override public int getInt() { return delegate.getInt(); }
    @Override public long getLong() { return delegate.getLong(); }
    @Override public BigDecimal getBigDecimal() { return delegate.getBigDecimal(); }
    @Override public JsonLocation getLocation() { return delegate.getLocation(); }
    @Override public JsonValue getValue() { return delegate.getValue(); }
    @Override public JsonObject getObject() { return delegate.getObject(); }
    @Override public JsonArray getArray() { return delegate.getArray(); }
    @Override public Stream<JsonValue> getArrayStream() { return delegate.getArrayStream(); }
    @Override public Stream<Map.Entry<String, JsonValue>> getObjectStream() { return delegate.getObjectStream(); }
    @Override public Stream<JsonValue> getValueStream() { return delegate.getValueStream(); }
    @Override public void skipArray() { delegate.skipArray(); }
    @Override public void skipObject() { delegate.skipObject(); }
    @Override public void close() { delegate.close(); }
}
