package io.vidocq.champollion.jsonb.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link JsonParser} qui rejoue un événement déjà consommé sur le parser sous-jacent.
 *
 * <p>Cas d'usage : un {@code JsonbDeserializer} custom a déjà appelé {@code parser.next()}
 * pour récupérer le {@code START_OBJECT}/{@code START_ARRAY}/{@code VALUE_xxx} initial,
 * puis appelle {@code ctx.deserialize(type, parser)}. La désérialisation standard
 * de Champollion fait {@code parser.next()} au début, ce qui consommerait l'élément
 * suivant (KEY_NAME, etc.). Ce wrapper restitue l'événement initial une fois.</p>
 */
final class ReplayJsonParser implements JsonParser {

    private final JsonParser delegate;
    private Event replay;

    ReplayJsonParser(Event replay, JsonParser delegate) {
        this.delegate = delegate;
        this.replay = replay;
    }

    @Override public boolean hasNext() { return replay != null || delegate.hasNext(); }

    @Override public Event next() {
        if (replay != null) {
            Event e = replay;
            replay = null;
            return e;
        }
        return delegate.next();
    }

    @Override public Event currentEvent() {
        return replay != null ? null : delegate.currentEvent();
    }

    @Override public String getString() { return delegate.getString(); }
    @Override public boolean isIntegralNumber() { return delegate.isIntegralNumber(); }
    @Override public int getInt() { return delegate.getInt(); }
    @Override public long getLong() { return delegate.getLong(); }
    @Override public BigDecimal getBigDecimal() { return delegate.getBigDecimal(); }
    @Override public JsonLocation getLocation() { return delegate.getLocation(); }
    @Override public JsonObject getObject() { return delegate.getObject(); }
    @Override public JsonValue getValue() { return delegate.getValue(); }
    @Override public JsonArray getArray() { return delegate.getArray(); }
    @Override public Stream<JsonValue> getArrayStream() { return delegate.getArrayStream(); }
    @Override public Stream<Map.Entry<String, JsonValue>> getObjectStream() { return delegate.getObjectStream(); }
    @Override public Stream<JsonValue> getValueStream() { return delegate.getValueStream(); }
    @Override public void skipArray() { delegate.skipArray(); }
    @Override public void skipObject() { delegate.skipObject(); }
    @Override public void close() { delegate.close(); }
}
