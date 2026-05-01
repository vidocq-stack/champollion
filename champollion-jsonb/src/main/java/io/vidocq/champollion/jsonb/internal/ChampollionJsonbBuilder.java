package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.spi.JsonProvider;

/**
 * {@link JsonbBuilder} Champollion. Pour l'instant la configuration et le
 * provider JSON-P sous-jacent sont fixes ; M4.1 = MVP. L'enrichissement de
 * {@link JsonbConfig} suivra.
 */
public final class ChampollionJsonbBuilder implements JsonbBuilder {

    private JsonbConfig config = new JsonbConfig();
    private JsonProvider jsonProvider;

    @Override public JsonbBuilder withConfig(JsonbConfig config) {
        this.config = config;
        return this;
    }

    @Override public JsonbBuilder withProvider(JsonProvider jsonProvider) {
        this.jsonProvider = jsonProvider;
        return this;
    }

    @Override public Jsonb build() {
        JsonProvider provider = jsonProvider != null ? jsonProvider : JsonProvider.provider();
        return new ChampollionJsonb(config, provider);
    }
}
